#ifndef MGBA_TEST_COMMON_H
#define MGBA_TEST_COMMON_H

#include <stdio.h>
#include <string.h>

static int g_failures;
static int g_checks;

#define CHECK(condition)                                                       \
    do {                                                                       \
        ++g_checks;                                                            \
        if (!(condition)) {                                                    \
            ++g_failures;                                                      \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__,            \
                    #condition);                                               \
        }                                                                      \
    } while (0)

#define CHECK_EQ_INT(actual, expected)                                         \
    do {                                                                       \
        long long actual_ = (long long)(actual);                               \
        long long expected_ = (long long)(expected);                           \
        ++g_checks;                                                            \
        if (actual_ != expected_) {                                            \
            ++g_failures;                                                      \
            fprintf(stderr, "FAIL %s:%d: %s == %s (got %lld, expected %lld)\n",\
                    __FILE__, __LINE__, #actual, #expected, actual_,           \
                    expected_);                                                \
        }                                                                      \
    } while (0)

#define CHECK_STR_EQ(actual, expected)                                         \
    do {                                                                       \
        const char *actual_ = (actual);                                        \
        const char *expected_ = (expected);                                    \
        ++g_checks;                                                            \
        if (actual_ == NULL || expected_ == NULL ||                            \
            strcmp(actual_, expected_) != 0) {                                 \
            ++g_failures;                                                      \
            fprintf(stderr, "FAIL %s:%d: %s == %s (got %s, expected %s)\n",    \
                    __FILE__, __LINE__, #actual, #expected,                    \
                    actual_ ? actual_ : "(null)",                              \
                    expected_ ? expected_ : "(null)");                         \
        }                                                                      \
    } while (0)

#define CHECK_NON_EMPTY(actual)                                                \
    do {                                                                       \
        const char *actual_ = (actual);                                        \
        ++g_checks;                                                            \
        if (actual_ == NULL || actual_[0] == '\0') {                           \
            ++g_failures;                                                      \
            fprintf(stderr, "FAIL %s:%d: %s is empty\n", __FILE__, __LINE__,   \
                    #actual);                                                  \
        }                                                                      \
    } while (0)

static int test_summary(const char *suite) {
    if (g_failures == 0) {
        printf("%s: %d checks passed\n", suite, g_checks);
        return 0;
    }
    fprintf(stderr, "%s: %d of %d checks failed\n", suite, g_failures, g_checks);
    return 1;
}

#endif /* MGBA_TEST_COMMON_H */
