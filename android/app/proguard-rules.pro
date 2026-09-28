# ONNX Runtime's native code looks up its Java classes by name, which its AAR does not keep.
-keep class ai.onnxruntime.** { *; }
