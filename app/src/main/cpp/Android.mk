LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := rylux_game_memory
LOCAL_SRC_FILES := sandbox_game_memory.cpp
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror -fvisibility=hidden
LOCAL_LDLIBS := -ldl -llog
include $(BUILD_SHARED_LIBRARY)
