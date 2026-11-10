#include <jni.h>

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
// Verified from the exact 610 ARM64 game library build ID above.
constexpr uintptr_t kPlayerGlobalRva = 0x1295468;
constexpr uintptr_t kGetItemWeightRva = 0x758d0c;
constexpr uintptr_t kGetFreeBagCountRva = 0x78b65c;

struct LoadedGameLibrary {
    uintptr_t loadAddress = 0;
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
        loaded->loadAddress = static_cast<uintptr_t>(info->dlpi_addr);
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

    // Resolve only RVAs validated against the exact game build ID above.
    // This runs inside the sandbox game process, so no root or cross-process
    // memory access is required, and avoids reopening a library from a linker namespace.
    const uintptr_t base = game.loadAddress;
    auto** playerGlobal = reinterpret_cast<void**>(base + kPlayerGlobalRva);
    auto getFreeSlots = reinterpret_cast<int (*)(void*)>(base + kGetFreeBagCountRva);
    auto getItemWeight = reinterpret_cast<int (*)(void*, int*, int*)>(
            base + kGetItemWeightRva);
    if (base == 0 || !readableRange(playerGlobal, sizeof(void*))
            || !readableRange(reinterpret_cast<void*>(getFreeSlots), 8)
            || !readableRange(reinterpret_cast<void*>(getItemWeight), 4)) {
        return fail("PROFILE_ADDRESS_UNREADABLE", game.buildId);
    }

    void* player = *playerGlobal;
    if (player == nullptr || !readableRange(player, kPlayerReadableSize)) {
        return fail("PLAYER_NOT_READY", game.buildId);
    }
    auto* bagList = static_cast<unsigned char*>(player) + kBagListOffset;
    void* inventory = *reinterpret_cast<void**>(bagList + kInventoryPointerOffset);
    if (inventory == nullptr || !readableRange(inventory, sizeof(void*))) {
        return fail("INVENTORY_NOT_READY", game.buildId);
    }

    // Both functions are read-only game getters. Reject transient or unready
    // values instead of forwarding them to the sell decision.
    const int freeSlots = getFreeSlots(player);
    int currentWeight = -1;
    int maxWeight = -1;
    getItemWeight(bagList, &currentWeight, &maxWeight);

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
