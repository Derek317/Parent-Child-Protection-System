#include "risk_core.h"

#include "llama.h"

#include <atomic>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

const llama_token kAnswerIds[RISK_N_ANSWERS] = {15, 16, 17, 18, 52};
const char* const kAnswerText[RISK_N_ANSWERS] = {"0", "1", "2", "3", "U"};

std::atomic<int> g_backend_users{0};

void set_err(char* err, size_t err_len, const std::string& msg) {
    if (err == nullptr || err_len == 0) return;
    std::snprintf(err, err_len, "%s", msg.c_str());
}

// Only errors get through.
void log_errors_only(ggml_log_level level, const char* text, void*) {
    if (level == GGML_LOG_LEVEL_ERROR && text != nullptr) std::fputs(text, stderr);
}

}  // namespace

struct risk_model {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    int n_ctx = 0;
};

risk_model* risk_load(const char* gguf_path, int n_threads, char* err, size_t err_len) {
    if (gguf_path == nullptr) {
        set_err(err, err_len, "no model path");
        return nullptr;
    }
    llama_log_set(log_errors_only, nullptr);
    if (g_backend_users.fetch_add(1) == 0) llama_backend_init();

    auto fail = [&](const std::string& msg) -> risk_model* {
        set_err(err, err_len, msg);
        if (g_backend_users.fetch_sub(1) == 1) llama_backend_free();
        return nullptr;
    };

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model* model = llama_model_load_from_file(gguf_path, mp);
    if (model == nullptr) return fail("cannot load model file");

    const llama_vocab* vocab = llama_model_get_vocab(model);
    for (int i = 0; i < RISK_N_ANSWERS; i++) {
        char piece[16];
        int n = vocab ? llama_token_to_piece(vocab, kAnswerIds[i], piece, sizeof(piece) - 1, 0, false) : -1;
        if (n < 0 || std::string(piece, (size_t)n) != kAnswerText[i]) {
            llama_model_free(model);
            return fail(std::string("model vocabulary mismatch: token id ") + std::to_string(kAnswerIds[i]) +
                        " is not \"" + kAnswerText[i] + "\"");
        }
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = RISK_CONTEXT_TOKENS;
    cp.n_batch = RISK_CONTEXT_TOKENS;  // whole prompt in one llama_decode call (split into ubatches internally)
    cp.n_threads = n_threads > 0 ? n_threads : 1;
    cp.n_threads_batch = cp.n_threads;
    cp.no_perf = true;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        llama_model_free(model);
        return fail("cannot create context");
    }

    risk_model* m = new risk_model();
    m->model = model;
    m->ctx = ctx;
    m->vocab = vocab;
    m->n_ctx = (int)llama_n_ctx(ctx);
    return m;
}

int risk_score_ex(risk_model* m, const char* prompt_utf8, float out_logits[RISK_N_ANSWERS], int* out_ids,
                  int out_ids_cap, int* out_n_tokens, char* err, size_t err_len) {
    if (m == nullptr || prompt_utf8 == nullptr || out_logits == nullptr) {
        set_err(err, err_len, "bad argument");
        return RISK_ERR_FAILED;
    }
    const int text_len = (int)std::strlen(prompt_utf8);

    // Size query: a negative return is minus the token count that would be produced.
    int32_t need = llama_tokenize(m->vocab, prompt_utf8, text_len, nullptr, 0, false, true);
    if (need == INT32_MIN) {
        set_err(err, err_len, "prompt too large to tokenize");
        return RISK_ERR_CONTEXT_TOO_LONG;
    }
    int n_tokens = need < 0 ? -need : need;
    if (out_n_tokens) *out_n_tokens = n_tokens;
    if (n_tokens == 0) {
        set_err(err, err_len, "empty prompt");
        return RISK_ERR_FAILED;
    }
    if (n_tokens > m->n_ctx) {
        set_err(err, err_len, "prompt of " + std::to_string(n_tokens) + " tokens does not fit " +
                                  std::to_string(m->n_ctx));
        return RISK_ERR_CONTEXT_TOO_LONG;
    }

    std::vector<llama_token> tokens((size_t)n_tokens);
    int32_t got = llama_tokenize(m->vocab, prompt_utf8, text_len, tokens.data(), n_tokens, false, true);
    if (got != n_tokens) {
        set_err(err, err_len, "tokenizer returned an unexpected count");
        return RISK_ERR_FAILED;
    }
    if (out_ids != nullptr)
        for (int i = 0; i < n_tokens && i < out_ids_cap; i++) out_ids[i] = tokens[(size_t)i];

    llama_memory_clear(llama_get_memory(m->ctx), true);

    // llama_decode and llama_batch_get_one are marked deprecated in b12503 (replaced by
    // llama_process + llama_batch_ext) but remain public; the pinned tag makes this safe.
#if defined(__clang__) || defined(__GNUC__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
#endif
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    int32_t rc = llama_decode(m->ctx, batch);
#if defined(__clang__) || defined(__GNUC__)
#pragma GCC diagnostic pop
#endif
    if (rc == 1) {
        set_err(err, err_len, "no KV slot for the prompt");
        return RISK_ERR_CONTEXT_TOO_LONG;
    }
    if (rc != 0) {
        set_err(err, err_len, "llama_decode failed with code " + std::to_string(rc));
        return RISK_ERR_FAILED;
    }
    const float* logits = llama_get_logits_ith(m->ctx, -1);
    if (logits == nullptr) {
        set_err(err, err_len, "no logits");
        return RISK_ERR_FAILED;
    }
    for (int i = 0; i < RISK_N_ANSWERS; i++) out_logits[i] = logits[kAnswerIds[i]];
    return RISK_OK;
}

int risk_score(risk_model* m, const char* prompt_utf8, float out_logits[RISK_N_ANSWERS], char* err,
               size_t err_len) {
    return risk_score_ex(m, prompt_utf8, out_logits, nullptr, 0, nullptr, err, err_len);
}

void risk_free(risk_model* m) {
    if (m == nullptr) return;
    if (m->ctx) llama_free(m->ctx);
    if (m->model) llama_model_free(m->model);
    delete m;
    if (g_backend_users.fetch_sub(1) == 1) llama_backend_free();
}
