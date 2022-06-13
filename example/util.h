#include <stdint.h>

inline uint64_t get_cycle()
{
    uint64_t cycle;
    asm volatile("rdcycle %0"
                 : "=r"(cycle));
    return cycle;
}

#define DONT_TOUCH(a) asm volatile("" ::"r"(a))

inline void load(void *ptr)
{
    DONT_TOUCH(*(volatile long *)ptr);
}

inline void store(void *ptr)
{
    *(volatile long *)ptr = 0;
}

#define L2CTL_BASE 0x2010000

typedef struct {
    uint64_t trains;
    uint64_t trainHits;
    uint64_t preds;
    uint64_t predGrants;
    uint64_t cacheables;
    uint64_t enables;
    uint64_t predValids;
    uint64_t clocks;
    uint64_t missAddr;
} l2perf_t;

typedef struct {
    // 0x000
    uint8_t banks;
    uint8_t ways;
    uint8_t lgSets;
    uint8_t lgBlockBytes;
    char _pad0[508];

    // 0x200
    uint64_t flush64;
    char _pad1[56];

    // 0x240
    uint32_t flush32;
    char _pad2[444];

    // 0x400
    struct {
        uint64_t read;
        uint64_t write;
        uint64_t enable;
    } prefetch;
    char _pad3[488];

    // 0x600
    l2perf_t perf;
} l2ctl_t;

#define TIMEIT(msg, body)                           \
    do {                                            \
        uint64_t cycle0 = get_cycle();              \
        body;                                       \
        uint64_t cycle1 = get_cycle();              \
        printf("%s: %llu\n", msg, cycle1 - cycle0); \
    } while (0)
