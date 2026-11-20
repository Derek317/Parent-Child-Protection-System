// Scoring core for IG Guard local risk scoring. Public llama.h C API only, no JNI.
// One forward pass over a prompt, raw next-token logits of the answer tokens 0 1 2 3 U.
// Not thread-safe per model: serialise calls on one risk_model*.
#ifndef IGGUARD_RISK_CORE_H
#define IGGUARD_RISK_CORE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct risk_model risk_model;

#define RISK_N_ANSWERS 5
#define RISK_CONTEXT_TOKENS 4096

enum {
    RISK_OK = 0,
    RISK_ERR_FAILED = 1,           // load, tokenize or decode failure; see err
    RISK_ERR_CONTEXT_TOO_LONG = 2  // the tokenized prompt does not fit the context
};

// Returns NULL on failure and writes a message to err (if err_len > 0).
// CPU only, context RISK_CONTEXT_TOKENS. Refuses a model whose token ids
// 15, 16, 17, 18, 52 do not detokenize to "0", "1", "2", "3", "U".
risk_model* risk_load(const char* gguf_path, int n_threads, char* err, size_t err_len);

// Tokenizes with add_special=false, parse_special=true, clears the KV cache, runs one
// llama_decode and writes the raw logits of ids 15 16 17 18 52 (order 0 1 2 3 U).
// If out_ids is non-NULL and out_ids_cap > 0, the token ids are copied there (up to cap)
// and *out_n_tokens (if non-NULL) receives the full token count; used by the host check.
// Returns RISK_OK, RISK_ERR_CONTEXT_TOO_LONG or RISK_ERR_FAILED.
int risk_score(risk_model* m, const char* prompt_utf8, float out_logits[RISK_N_ANSWERS],
               char* err, size_t err_len);

// Same as risk_score, additionally returning the token ids (host check only).
int risk_score_ex(risk_model* m, const char* prompt_utf8, float out_logits[RISK_N_ANSWERS],
                  int* out_ids, int out_ids_cap, int* out_n_tokens, char* err, size_t err_len);

// Same, with an explicit byte length: the prompt may contain NUL bytes and is not measured with strlen.
// risk_score and risk_score_ex stop at the first NUL; the app (JNI) and the host check use this one.
int risk_score_n(risk_model* m, const char* prompt_utf8, int prompt_len, float out_logits[RISK_N_ANSWERS],
                 int* out_ids, int out_ids_cap, int* out_n_tokens, char* err, size_t err_len);

void risk_free(risk_model* m);

#ifdef __cplusplus
}
#endif
#endif
