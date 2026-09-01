/* Formats OpenConnect progress varargs and forwards to Rust. */
#include <stdio.h>
#include <stdarg.h>

void rust_oc_progress(void *priv, int level, const char *msg);

void oc_progress_trampoline(void *priv, int level, const char *fmt, ...) {
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
#if defined(_MSC_VER)
    vsnprintf_s(buf, sizeof(buf), _TRUNCATE, fmt, ap);
#else
    vsnprintf(buf, sizeof(buf), fmt, ap);
#endif
    va_end(ap);
    rust_oc_progress(priv, level, buf);
}
