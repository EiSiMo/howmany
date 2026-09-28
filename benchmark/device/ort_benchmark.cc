// Measures an ONNX model on an Android phone's CPU the way the app runs it, in the manner of
// LiteRT's benchmark_model: session creation, warm-up runs, then timed runs.
//
// Usage: ort_benchmark MODEL IMAGE_RAW EXEMPLARS_RAW THREADS WARMUP_RUNS RUNS
// The raw files hold the float32 inputs "image" (1, 3, 1024, 1024) and "exemplars" (1, 1, 4).
// Links against libonnxruntime.so from the onnxruntime-android AAR that the app ships, with the
// app's session options: THREADS intra-op threads, memory pattern optimization off.

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

#include "onnxruntime_cxx_api.h"

namespace {

using Clock = std::chrono::steady_clock;

std::vector<float> ReadFloats(const char* path, size_t count) {
  std::ifstream file(path, std::ios::binary);
  std::vector<char> bytes((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
  if (!file.good() && !file.eof()) throw std::runtime_error(std::string("Cannot read ") + path);
  if (bytes.size() != count * sizeof(float)) {
    throw std::runtime_error(std::string(path) + " has " + std::to_string(bytes.size()) +
                             " bytes, expected " + std::to_string(count * sizeof(float)));
  }
  std::vector<float> values(count);
  std::copy(bytes.begin(), bytes.end(), reinterpret_cast<char*>(values.data()));
  return values;
}

double Milliseconds(Clock::duration duration) {
  return std::chrono::duration<double, std::milli>(duration).count();
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 7) {
    std::fprintf(stderr, "Usage: %s MODEL IMAGE_RAW EXEMPLARS_RAW THREADS WARMUP_RUNS RUNS\n",
                 argv[0]);
    return 2;
  }
  try {
    const int threads = std::atoi(argv[4]), warmup_runs = std::atoi(argv[5]),
              runs = std::atoi(argv[6]);
    const std::vector<int64_t> image_shape{1, 3, 1024, 1024}, exemplars_shape{1, 1, 4};
    std::vector<float> image = ReadFloats(argv[2], 3 * 1024 * 1024);
    std::vector<float> exemplars = ReadFloats(argv[3], 4);

    Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "ort_benchmark");
    Ort::SessionOptions options;
    options.SetIntraOpNumThreads(threads);
    options.DisableMemPattern();
    const auto load_start = Clock::now();
    Ort::Session session(env, argv[1], options);
    const double load_ms = Milliseconds(Clock::now() - load_start);

    const auto memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    Ort::Value inputs[] = {
        Ort::Value::CreateTensor<float>(memory, image.data(), image.size(), image_shape.data(),
                                        image_shape.size()),
        Ort::Value::CreateTensor<float>(memory, exemplars.data(), exemplars.size(),
                                        exemplars_shape.data(), exemplars_shape.size()),
    };
    const char* input_names[] = {"image", "exemplars"};
    const char* output_names[] = {"objectness", "offsets"};

    std::vector<double> times;
    for (int run = 0; run < warmup_runs + runs; ++run) {
      const auto start = Clock::now();
      session.Run(Ort::RunOptions{nullptr}, input_names, inputs, 2, output_names, 2);
      if (run >= warmup_runs) times.push_back(Milliseconds(Clock::now() - start));
    }
    std::sort(times.begin(), times.end());
    double total = 0;
    for (double time : times) total += time;
    std::printf("load_ms=%.0f runs=%zu median_ms=%.0f min_ms=%.0f max_ms=%.0f mean_ms=%.0f\n",
                load_ms, times.size(), times[times.size() / 2], times.front(), times.back(),
                total / times.size());
  } catch (const std::exception& error) {
    std::fprintf(stderr, "ort_benchmark failed: %s\n", error.what());
    return 1;
  }
  return 0;
}
