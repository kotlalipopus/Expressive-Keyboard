LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := materialboard_quickjs
LOCAL_SRC_FILES := plugin_quickjs.c quickjs/quickjs.c quickjs/cutils.c quickjs/dtoa.c \
    quickjs/libregexp.c quickjs/libunicode.c
LOCAL_CFLAGS := -D_GNU_SOURCE -DCONFIG_VERSION=\"2026-06-04\" -fvisibility=hidden
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)
