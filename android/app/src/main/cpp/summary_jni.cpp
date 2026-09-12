#include <jni.h>
#include "llama.h"
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

static std::string bytes(JNIEnv *env, jbyteArray input) {
    std::string result(env->GetArrayLength(input), '\0');
    env->GetByteArrayRegion(input, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_gongfpp_sonfolio_summary_LocalSummaryNative_generate(JNIEnv *env, jobject, jstring path, jbyteArray system, jbyteArray user) {
    try {
        static std::once_flag init;
        std::call_once(init, [] { llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr); llama_backend_init(); });
        const char *raw = env->GetStringUTFChars(path, nullptr);
        std::string model_path(raw); env->ReleaseStringUTFChars(path, raw);
        auto mp = llama_model_default_params(); mp.n_gpu_layers = 0;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(model_path.c_str(), mp), llama_model_free);
        if (!model) throw std::runtime_error("model");
        const auto *vocab = llama_model_get_vocab(model.get());
        auto cp = llama_context_default_params();
        cp.n_ctx = 4096; cp.n_batch = 256; cp.n_ubatch = 128; cp.n_threads = 2; cp.n_threads_batch = 2;
        auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(150);
        cp.abort_callback = [](void *data) { return std::chrono::steady_clock::now() > *static_cast<std::chrono::steady_clock::time_point *>(data); };
        cp.abort_callback_data = &deadline;
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(model.get(), cp), llama_free);
        if (!ctx) throw std::runtime_error("context");
        std::string instruction = bytes(env, system), content = bytes(env, user);
        llama_chat_message messages[] = {{"system", instruction.c_str()}, {"user", content.c_str()}};
        const char *tmpl = llama_model_chat_template(model.get(), nullptr);
        if (!tmpl) throw std::runtime_error("template");
        int size = llama_chat_apply_template(tmpl, messages, 2, true, nullptr, 0);
        if (size <= 0 || size > 100000) throw std::runtime_error("template");
        std::vector<char> formatted(size + 1);
        size = llama_chat_apply_template(tmpl, messages, 2, true, formatted.data(), formatted.size());
        if (size <= 0 || size >= static_cast<int>(formatted.size())) throw std::runtime_error("template");
        int count = -llama_tokenize(vocab, formatted.data(), size, nullptr, 0, true, true);
        constexpr int max_output = 768;
        if (count <= 0 || count + max_output > 4096) throw std::runtime_error("too long");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, formatted.data(), size, tokens.data(), count, true, true) < 0) throw std::runtime_error("tokens");
        const char *grammar = R"GBNF(root ::= "{" ws "\"title\"" ws ":" ws str "," ws "\"brief\"" ws ":" ws str "," ws "\"keyPoints\"" ws ":" ws arr "," ws "\"decisions\"" ws ":" ws arr "," ws "\"followUps\"" ws ":" ws arr "," ws "\"questions\"" ws ":" ws arr "}" ws
str ::= "\"" ([^"\\\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4}))* "\""
arr ::= "[" ws (str ("," ws str){0,2})? "]"
ws ::= [ \t\n\r]*
)GBNF";
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        auto *constraint = llama_sampler_init_grammar(vocab, grammar, "root");
        if (!constraint) throw std::runtime_error("grammar");
        llama_sampler_chain_add(sampler.get(), constraint);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        for (int offset = 0; offset < count; offset += 256) {
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(256, count - offset));
            if (llama_decode(ctx.get(), batch) != 0) throw std::runtime_error("decode");
        }
        std::string output;
        bool ended = false;
        for (int i = 0; i < max_output; i++) {
            if (std::chrono::steady_clock::now() > deadline) throw std::runtime_error("timeout");
            llama_token token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
            std::vector<char> piece(512);
            int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (n < 0) { piece.resize(-n); n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
            if (n < 0) throw std::runtime_error("piece");
            output.append(piece.data(), n);
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(ctx.get(), batch) != 0) throw std::runtime_error("decode");
        }
        if (!ended) throw std::runtime_error("incomplete");
        auto result = env->NewByteArray(output.size());
        env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (...) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Local model failed: incompatible model, context limit, memory or timeout");
        return nullptr;
    }
}
