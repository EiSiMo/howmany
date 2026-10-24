// Native heap settings for the model's memory. See run.moritz.howmany.counting.NativeMemory.
#include <jni.h>
#include <malloc.h>

JNIEXPORT jboolean JNICALL
Java_run_moritz_howmany_counting_NativeMemory_returnFreedAtOnce(JNIEnv *env, jobject this) {
    return mallopt(M_DECAY_TIME, 0) == 1;
}
