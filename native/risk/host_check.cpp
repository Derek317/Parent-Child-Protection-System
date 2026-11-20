// host_check <gguf> <prompt-file>: prints token ids, the five raw logits and their softmax as JSON.
// Build for the Linux host with native/risk/CMakeLists.txt -DRISK_HOST_CHECK=ON.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <vector>

#include "risk_core.h"

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: host_check <gguf> <prompt-file> [threads]\n");
        return 2;
    }
    std::ifstream f(argv[2], std::ios::binary);
    std::stringstream ss;
    ss << f.rdbuf();
    std::string prompt = ss.str();
    char err[256] = {0};
    risk_model* m = risk_load(argv[1], argc > 3 ? std::atoi(argv[3]) : 4, err, sizeof(err));
    if (!m) {
        std::fprintf(stderr, "load failed: %s\n", err);
        return 1;
    }
    std::vector<int> ids(RISK_CONTEXT_TOKENS);
    int n = 0;
    float logits[RISK_N_ANSWERS];
    int rc = risk_score_n(m, prompt.data(), (int)prompt.size(), logits, ids.data(), (int)ids.size(), &n, err, sizeof(err));
    if (rc != RISK_OK) {
        std::fprintf(stderr, "score failed (%d): %s\n", rc, err);
        risk_free(m);
        return rc == RISK_ERR_CONTEXT_TOO_LONG ? 3 : 1;
    }
    float mx = *std::max_element(logits, logits + RISK_N_ANSWERS);
    double sum = 0, e[RISK_N_ANSWERS];
    for (int i = 0; i < RISK_N_ANSWERS; i++) sum += (e[i] = std::exp((double)logits[i] - mx));
    std::printf("{\"n_tokens\":%d,\"ids\":[", n);
    for (int i = 0; i < n; i++) std::printf("%s%d", i ? "," : "", ids[(size_t)i]);
    std::printf("],\"logits\":[");
    for (int i = 0; i < RISK_N_ANSWERS; i++) std::printf("%s%.9g", i ? "," : "", logits[i]);
    std::printf("],\"probs\":[");
    for (int i = 0; i < RISK_N_ANSWERS; i++) std::printf("%s%.9g", i ? "," : "", e[i] / sum);
    std::printf("]}\n");
    risk_free(m);
    return 0;
}
