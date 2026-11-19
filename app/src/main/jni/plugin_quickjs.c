#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "quickjs/quickjs.h"

enum RunMode { BOOTSTRAP, EVAL, DISPATCH };

typedef struct {
    JSRuntime *runtime;
    JSContext *context;
    JSValue dispatcher;
    JNIEnv *env;
    jobject owner;
    jmethodID dispatch_host;
    int64_t deadline;
    int depth;
    int interrupted;
} PluginRuntime;

static int64_t monotonic_ms(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return (int64_t)time.tv_sec * 1000 + time.tv_nsec / 1000000;
}

static int check_deadline(JSRuntime *runtime, void *opaque) {
    (void)runtime;
    PluginRuntime *plugin = opaque;
    if (monotonic_ms() >= plugin->deadline) plugin->interrupted = 1;
    return plugin->interrupted;
}

static JSValue string_from_bytes(JSContext *ctx, JNIEnv *env, jbyteArray bytes) {
    jsize length = (*env)->GetArrayLength(env, bytes);
    jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (!data) return JS_ThrowOutOfMemory(ctx);
    JSValue result = JS_NewStringLen(ctx, (const char *)data, length);
    (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
    return result;
}

static JSValue dispatch_host(JSContext *ctx, JSValueConst self, int argc, JSValueConst *argv) {
    (void)self;
    PluginRuntime *plugin = JS_GetContextOpaque(ctx);
    JNIEnv *env = plugin->env;
    int32_t id;
    if (argc != 2 || JS_ToInt32(ctx, &id, argv[0]) < 0) {
        return JS_ThrowTypeError(ctx, "Invalid host call");
    }
    size_t length;
    const char *json = JS_ToCStringLen(ctx, &length, argv[1]);
    if (!json) return JS_EXCEPTION;
    if (length > 16 * 1024 * 1024) {
        JS_FreeCString(ctx, json);
        return JS_ThrowRangeError(ctx, "Plugin data exceeds 16 MiB");
    }
    jbyteArray payload = (*env)->NewByteArray(env, (jsize)length);
    if (payload) (*env)->SetByteArrayRegion(env, payload, 0, (jsize)length, (const jbyte *)json);
    JS_FreeCString(ctx, json);
    if (!payload || (*env)->ExceptionCheck(env)) return JS_ThrowOutOfMemory(ctx);

    jbyteArray response = (*env)->CallObjectMethod(env, plugin->owner, plugin->dispatch_host, id, payload);
    (*env)->DeleteLocalRef(env, payload);
    if (!response || (*env)->ExceptionCheck(env)) return JS_ThrowInternalError(ctx, "Host call failed");
    JSValue result = string_from_bytes(ctx, env, response);
    (*env)->DeleteLocalRef(env, response);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_expressive_keyboard_QuickJsPluginRuntime_nativeCreate(JNIEnv *env, jobject owner) {
    jclass cls = (*env)->GetObjectClass(env, owner);
    jmethodID dispatch = (*env)->GetMethodID(env, cls, "dispatchHost", "(I[B)[B");
    if (!dispatch) return 0;
    PluginRuntime *plugin = calloc(1, sizeof(*plugin));
    if (!plugin) return 0;
    plugin->runtime = JS_NewRuntime();
    if (!plugin->runtime) {
        free(plugin);
        return 0;
    }
    JS_SetMemoryLimit(plugin->runtime, 64 * 1024 * 1024);
    JS_SetMaxStackSize(plugin->runtime, 256 * 1024);
    JS_SetInterruptHandler(plugin->runtime, check_deadline, plugin);
    plugin->context = JS_NewContext(plugin->runtime);
    if (!plugin->context) {
        JS_FreeRuntime(plugin->runtime);
        free(plugin);
        return 0;
    }
    JS_SetContextOpaque(plugin->context, plugin);
    plugin->dispatch_host = dispatch;
    plugin->dispatcher = JS_UNDEFINED;
    return (jlong)(intptr_t)plugin;
}

static JSValue evaluate(PluginRuntime *plugin, JNIEnv *env, jint mode, jbyteArray code, jstring filename) {
    JSContext *ctx = plugin->context;
    if (mode == DISPATCH) {
        JSValue request = string_from_bytes(ctx, env, code);
        if (JS_IsException(request)) return request;
        JSValue result = JS_Call(ctx, plugin->dispatcher, JS_UNDEFINED, 1, &request);
        JS_FreeValue(ctx, request);
        return result;
    }
    if (mode == BOOTSTRAP) {
        JSValue global = JS_GetGlobalObject(ctx);
        int set = JS_SetPropertyStr(ctx, global, "__materialBoardHost",
                                   JS_NewCFunction(ctx, dispatch_host, "", 2));
        JS_FreeValue(ctx, global);
        if (set < 0) return JS_EXCEPTION;
    }

    jsize length = (*env)->GetArrayLength(env, code);
    // JS_Eval requires a trailing NUL even when a byte count is supplied.
    char *source = malloc((size_t)length + 1);
    if (!source) return JS_ThrowOutOfMemory(ctx);
    (*env)->GetByteArrayRegion(env, code, 0, length, (jbyte *)source);
    source[length] = '\0';
    const char *name = (*env)->GetStringUTFChars(env, filename, NULL);
    JSValue result = name ? JS_Eval(ctx, source, length, name, JS_EVAL_TYPE_GLOBAL) : JS_ThrowOutOfMemory(ctx);
    if (name) (*env)->ReleaseStringUTFChars(env, filename, name);
    free(source);
    return result;
}

static jbyteArray response_bytes(JNIEnv *env, int failed, const char *text, size_t length) {
    jbyteArray response = (*env)->NewByteArray(env, (jsize)length + 1);
    if (!response) return NULL;
    jbyte status = failed;
    (*env)->SetByteArrayRegion(env, response, 0, 1, &status);
    if (length) (*env)->SetByteArrayRegion(env, response, 1, (jsize)length, (const jbyte *)text);
    return response;
}

JNIEXPORT jbyteArray JNICALL
Java_com_expressive_keyboard_QuickJsPluginRuntime_nativeRun(
        JNIEnv *env, jobject owner, jlong handle, jint mode, jbyteArray code, jstring filename, jlong budget) {
    PluginRuntime *plugin = (PluginRuntime *)(intptr_t)handle;
    JSContext *ctx = plugin->context;
    int nested = plugin->depth++;
    int64_t previous_deadline = plugin->deadline;
    int previous_interrupt = plugin->interrupted;
    plugin->deadline = monotonic_ms() + budget;
    if (!nested) {
        plugin->env = env;
        plugin->owner = owner;
        plugin->interrupted = 0;
        JS_UpdateStackTop(plugin->runtime);
    } else if (previous_deadline < plugin->deadline) {
        plugin->deadline = previous_deadline;
    }

    JSValue result = evaluate(plugin, env, mode, code, filename);
    JSContext *error_ctx = ctx;
    int failed = JS_IsException(result);
    if (!failed && mode == BOOTSTRAP) {
        plugin->dispatcher = JS_DupValue(ctx, result);
    }
    if (!failed && !nested && !(*env)->ExceptionCheck(env)) {
        while (JS_IsJobPending(plugin->runtime) && !check_deadline(plugin->runtime, plugin)) {
            if (JS_ExecutePendingJob(plugin->runtime, &error_ctx) < 0) {
                failed = 1;
                break;
            }
            if ((*env)->ExceptionCheck(env)) break;
        }
    }

    jbyteArray response = NULL;
    if (!check_deadline(plugin->runtime, plugin) && !(*env)->ExceptionCheck(env)) {
        if (failed) {
            JSValue error = JS_GetException(error_ctx);
            size_t length = 0;
            const char *message = JS_ToCStringLen(error_ctx, &length, error);
            if (message) {
                response = response_bytes(env, 1, message, length);
                JS_FreeCString(error_ctx, message);
            } else if (!plugin->interrupted) {
                const char *fallback = "QuickJS execution failed";
                response = response_bytes(env, 1, fallback, strlen(fallback));
            }
            JS_FreeValue(error_ctx, error);
        } else if (mode == DISPATCH) {
            size_t length = 0;
            const char *json = JS_ToCStringLen(ctx, &length, result);
            if (json) {
                response = response_bytes(env, 0, json, length);
                JS_FreeCString(ctx, json);
            }
        } else {
            response = response_bytes(env, 0, "", 0);
        }
    } else if (failed) {
        JS_FreeValue(error_ctx, JS_GetException(error_ctx));
    }
    JS_FreeValue(ctx, result);
    plugin->depth--;
    plugin->interrupted = previous_interrupt;
    plugin->deadline = previous_deadline;
    if (!nested) {
        plugin->owner = NULL;
        plugin->env = NULL;
    }
    return response;
}

JNIEXPORT void JNICALL
Java_com_expressive_keyboard_QuickJsPluginRuntime_nativeClose(JNIEnv *env, jobject owner, jlong handle) {
    (void)env;
    (void)owner;
    PluginRuntime *plugin = (PluginRuntime *)(intptr_t)handle;
    JS_FreeValue(plugin->context, plugin->dispatcher);
    JS_FreeContext(plugin->context);
    JS_FreeRuntime(plugin->runtime);
    free(plugin);
}
