// Ponte JNI tra Kotlin (LameNative.kt) e l'encoder LAME.
#include <jni.h>
#include <stdint.h>
#include "lame.h"

#define G(h) ((lame_t)(intptr_t)(h))

JNIEXPORT jlong JNICALL
Java_it_sam_sunodownloader_LameNative_create(JNIEnv *env, jclass cls, jint inRate,
                                           jint channels, jint bitrate, jint quality) {
    lame_t g = lame_init();
    if (!g) return 0;
    lame_set_in_samplerate(g, inRate);
    lame_set_num_channels(g, channels);
    switch (inRate) {  // frequenze MP3 standard: mantienile; altrimenti LAME ricampiona
        case 8000: case 11025: case 12000: case 16000: case 22050:
        case 24000: case 32000: case 44100: case 48000:
            lame_set_out_samplerate(g, inRate);
            break;
        default:
            break;
    }
    lame_set_VBR(g, vbr_off);           // CBR come ffmpeg -b:a
    lame_set_brate(g, bitrate);
    lame_set_quality(g, quality);
    lame_set_mode(g, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_bWriteVbrTag(g, 0);        // niente frame Xing vuoto in testa
    if (lame_init_params(g) < 0) {
        lame_close(g);
        return 0;
    }
    return (jlong)(intptr_t)g;
}

// pcm: campioni 16 bit (interleaved se stereo); samples = campioni PER CANALE
JNIEXPORT jint JNICALL
Java_it_sam_sunodownloader_LameNative_encode(JNIEnv *env, jclass cls, jlong h,
                                             jshortArray pcm, jint samples, jbyteArray out) {
    lame_t g = G(h);
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    jsize osz = (*env)->GetArrayLength(env, out);
    int n;
    if (lame_get_num_channels(g) == 1)
        n = lame_encode_buffer(g, in, in, samples, (unsigned char *)o, osz);
    else
        n = lame_encode_buffer_interleaved(g, in, samples, (unsigned char *)o, osz);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_it_sam_sunodownloader_LameNative_flush(JNIEnv *env, jclass cls, jlong h, jbyteArray out) {
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    jsize osz = (*env)->GetArrayLength(env, out);
    int n = lame_encode_flush(G(h), (unsigned char *)o, osz);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return n;
}

JNIEXPORT void JNICALL
Java_it_sam_sunodownloader_LameNative_close(JNIEnv *env, jclass cls, jlong h) {
    if (h) lame_close(G(h));
}

JNIEXPORT jstring JNICALL
Java_it_sam_sunodownloader_LameNative_version(JNIEnv *env, jclass cls) {
    return (*env)->NewStringUTF(env, get_lame_version());
}
