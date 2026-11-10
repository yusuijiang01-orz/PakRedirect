#include <jni.h>

#include <dlfcn.h>
#include <link.h>
#include <elf.h>

#include <cstdio>
#include <cstdint>
#include <cstring>
#include <string>

namespace {

constexpr char kLibraryName[] = "libcocos2dcpp.so";
constexpr char kFreeSlotsSymbol[] = "_ZN7KPlayer15getFreeBagCountEv";
constexpr char kWeightSymbol[] = "_ZN9KItemList13GetItemWeightEPiS0_";
constexpr uintptr_t kBagListOffset = 0x70;
constexpr uintptr_t kInventoryPointerOffset = 0x3c10;
constexpr size_t kPlayerReadableSize = kBagListOffset + kInventoryPointerOffset + sizeof(void*);

struct LoadedGameLibrary {
    std::string path;
    std::string buildId;
    int matchCount = 0;
};

std::string readBuildId(const dl_phdr_info* info) {
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr)& header = info->dlpi_phdr[i];
        if (header.p_type != PT_NOTE || header.p_memsz < sizeof(ElfW(Nhdr))) continue;
        const auto* cursor = reinterpret_cast<const unsigned char*>(info->dlpi_addr + header.p_vaddr);
        const auto* end = cursor + header.p_memsz;
        while (cursor + sizeof(ElfW(Nhdr)) <= end) {
            const auto* note = reinterpret_cast<const ElfW(Nhdr)*>(cursor);
            cursor += sizeof(ElfW(Nhdr));
            const size_t nameSize = note->n_namesz;
            const size_t descSize = note->n_descsz;
            const size_t paddedName = (nameSize + 3U) & ~size_t(3U);
            const size_t paddedDesc = (descSize + 3U) & ~size_t(3U);
            if (cursor + paddedName + paddedDesc > end) break;
            const auto* name = cursor;
            const auto* desc = cursor + paddedName;
            if (note->n_type == NT_GNU_BUILD_ID && nameSize >= 3
                    && name[0] == 'G' && name[1] == 'N' && name[2] == 'U'
                    && descSize > 0 && descSize <= 64) {
                static constexpr char hex[] = "0123456789abcdef";
                std::string result;
                result.reserve(descSize * 2);
                for (size_t j = 0; j < descSize; ++j) {
                    result.push_back(hex[desc[j] >> 4]);
                    result.push_back(hex[desc[j] & 0x0f]);
                }
                return result;
            }
            cursor += paddedName + paddedDesc;
        }
    }
    return {};
}

int findGameLibrary(dl_phdr_info* info, size_t, void* opaque) {
    auto* loaded = static_cast<LoadedGameLibrary*>(opaque);
    const char* name = info->dlpi_name;
    if (name == nullptr || name[0] == '\0') return 0;
    const char* slash = std::strrchr(name, '/');
    const char* basename = slash == nullptr ? name : slash + 1;
    if (std::strcmp(basename, kLibraryName) != 0) return 0;
    ++loaded->matchCount;
    if (loaded->matchCount == 1) {
        loaded->path = name;
        loaded->buildId = readBuildId(info);
    }
    return 0;
}

bool readableRange(const void* address, size_t length) {
    if (address == nullptr || length == 0) return false;
    const uintptr_t begin = reinterpret_cast<uintptr_t>(address);
    if (begin > UINTPTR_MAX - length) return false;
    const uintptr_t end = begin + length;
    FILE* maps = std::fopen("/proc/self/maps", "r");
    if (maps == nullptr) return false;
    char line[512];
    bool readable = false;
    while (std::fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long rangeBegin = 0;
        unsigned long long rangeEnd = 0;
        char permissions[5] = {};
        if (std::sscanf(line, "%llx-%llx %4s", &rangeBegin, &rangeEnd, permissions) != 3) continue;
        if (permissions[0] == 'r' && begin >= rangeBegin && end <= rangeEnd) {
            readable = true;
            break;
        }
    }
    std::fclose(maps);
    return readable;
}

bool supportedBuild(const std::string& buildId) {
    // Only the validated 610 ARM64 binary is enabled. 587 and all unknown
    // builds fail closed until a later-version profile is independently checked.
    return buildId == "350ee73d222f974988c13b5917337fb2d96fe68a";
}

std::string fail(const char* status, const std::string& buildId = {}) {
    return std::string(status) + "|" + buildId;
}

std::string takeSnapshot() {
    LoadedGameLibrary game;
    dl_iterate_phdr(findGameLibrary, &game);
    if (game.matchCount == 0) return fail("WAIT");
    if (game.matchCount != 1) return fail("UNSUPPORTED_MULTIPLE_LIBRARIES", game.buildId);
    if (!supportedBuild(game.buildId)) return fail("UNSUPPORTED_BUILD", game.buildId);

    // RTLD_NOLOAD prevents loading or initializing a second game library.
    void* handle = dlopen(game.path.c_str(), RTLD_NOW | RTLD_NOLOAD);
    if (handle == nullptr) {
        const char* error = dlerror();
        return fail(error == nullptr ? "DLOPEN_FAILED" : (std::string("DLOPEN_FAILED:") + error).c_str(),
                game.buildId);
    }
    auto closeHandle = [&handle]() {
        if (handle != nullptr) {
            dlclose(handle);
            handle = nullptr;
        }
    };

    dlerror();
    auto** playerGlobal = static_cast<void**>(dlsym(handle, "Player"));
    const char* playerError = dlerror();
    dlerror();
    auto getFreeSlots = reinterpret_cast<int (*)(void*)>(dlsym(handle, kFreeSlotsSymbol));
    const char* freeSlotsError = dlerror();
    dlerror();
    auto getItemWeight = reinterpret_cast<int (*)(void*, int*, int*)>(dlsym(handle, kWeightSymbol));
    const char* weightError = dlerror();
    if (playerGlobal == nullptr || playerError != nullptr) {
        closeHandle();
        return fail("PLAYER_SYMBOL_MISSING", game.buildId);
    }
    if (getFreeSlots == nullptr || freeSlotsError != nullptr) {
        closeHandle();
        return fail("FREE_SLOT_SYMBOL_MISSING", game.buildId);
    }
    if (getItemWeight == nullptr || weightError != nullptr) {
        closeHandle();
        return fail("WEIGHT_SYMBOL_MISSING", game.buildId);
    }
    if (!readableRange(playerGlobal, sizeof(void*))) {
        closeHandle();
        return fail("PLAYER_GLOBAL_UNREADABLE", game.buildId);
    }

    void* player = *playerGlobal;
    if (player == nullptr || !readableRange(player, kPlayerReadableSize)) {
        closeHandle();
        return fail("PLAYER_NOT_READY", game.buildId);
    }
    auto* bagList = static_cast<unsigned char*>(player) + kBagListOffset;
    void* inventory = *reinterpret_cast<void**>(bagList + kInventoryPointerOffset);
    if (inventory == nullptr || !readableRange(inventory, sizeof(void*))) {
        closeHandle();
        return fail("INVENTORY_NOT_READY", game.buildId);
    }

    // Both functions are read-only game getters. Reject transient or unready
    // values instead of forwarding them to the sell decision.
    const int freeSlots = getFreeSlots(player);
    int currentWeight = -1;
    int maxWeight = -1;
    getItemWeight(bagList, &currentWeight, &maxWeight);
    closeHandle();

    if (freeSlots < 0 || freeSlots > 200 || currentWeight < 0
            || currentWeight > 1000000 || maxWeight <= 0 || maxWeight > 1000000) {
        return fail("VALUES_NOT_READY", game.buildId);
    }
    char values[96];
    std::snprintf(values, sizeof(values), "OK|%s|%d|%d|%d", game.buildId.c_str(),
            freeSlots, currentWeight, maxWeight);
    return values;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_pakredirect_SandboxGameMemoryNative_readSnapshot(JNIEnv* env, jclass) {
    const std::string result = takeSnapshot();
    return env->NewStringUTF(result.c_str());
}
