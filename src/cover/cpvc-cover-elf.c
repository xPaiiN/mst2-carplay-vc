/*
 * Made by xPaiiN <3
 * github.com/xPaiiN/mst2-carplay-vc
 * v2.0
 */
#define _QNX_SOURCE
#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <time.h>
#include <errno.h>
#include <sys/stat.h>

#define IAP2_LOG        "/dev/shmem/iap2_carplay.log"

#define COVER_DEST      "/ramdisk/carplayvc/cover.jpg"
#define COVER_TMP       "/ramdisk/carplayvc/cover.jpg.tmp"
#define LOG_QUIET       "/ramdisk/carplayvc/cpvc-cover-bridge.log"
#define LOG_DEBUG       "/dev/shmem/cpvc-cover-bridge.log"
#define LOG_CAP_QUIET   (64L * 1024L)
#define LOG_CAP_DEBUG   (2048L * 1024L)
#define PID_FILE        "/ramdisk/carplayvc/cpvc-cover-bridge.pid"
#define VAR_DIR         "/ramdisk/carplayvc"
#define COVER_STATE     "/ramdisk/carplayvc/cover-current.path"
#define COVER_STATE_TMP "/ramdisk/carplayvc/cover-current.path.tmp"
#define COVER_TARGET_SIZE "/ramdisk/carplayvc/cover-target-size"
#define COVER_LINK_PFX  "/ramdisk/carplayvc/cover-"
#define HARDLINK_KEEP   2

#define SCAN_WINDOW    (3072 * 1024)
#define COVER_TRUNC_BUFLEN  (128 * 1024)
#define MAX_TRUNC_HOLD_SCANS 5
#define NOCOVER_GRACE_SCANS 4
#define RELAXED_PICK_LOG_TAG "pick-relaxed"
#define MIN_JPEG_SIZE    1024
#define MAX_JPEG_SIZE   (320 * 1024)
#define POLL_INTERVAL        2
#define IDLE_CHECK_INTERVAL  1
#define IDLE_FULL_SCAN_EVERY 10
#define COMPLETE_LOOKBACK_SCANS 4
#define RECOVERY_INTERVAL_SCANS 4
#define BOOT_DELAY      10
#define ALIVE_LOG_EVERY 60
#define MAX_SLOTS        8
#define SLOT_IDLE_SECS  20
#define ANCHOR_SCAN     64
#define SETUP_TOLERANCE 10
#define COVER_TARGET_TOL 8

typedef enum {
    SLOT_FREE = 0,
    SLOT_SETUP,
    SLOT_FIRST_OK,
    SLOT_DATA,
    SLOT_COMPLETE,
    SLOT_TRUNCATED,
    SLOT_CORRUPT_FIRST
} SlotState;

typedef struct {
    int        id;
    SlotState  state;
    uint32_t   expected_size;
    uint32_t   actual_size;
    time_t     last_seen;
    uint32_t   complete_scan;
    size_t     complete_off;
    uint8_t    buf[MAX_JPEG_SIZE];
} File;

static File      slots[MAX_SLOTS];
static volatile int running = 1;
static uint32_t  last_global_hash = 0;
static int       last_parse_tid_race = 0;
static uint32_t  scan_counter = 0;
static time_t    JVM_LIKE_START = 0;
static uint32_t  last_logged_target = 0;
static uint32_t  last_recovery_scan = 0;

typedef enum { MODE_ACTIVE = 0, MODE_IDLE = 1 } TapMode;
static TapMode tap_mode = MODE_ACTIVE;
static uint32_t last_target_seen = 0;
static uint32_t idle_full_scan_counter = 0;
static uint32_t no_match_scan_count = 0;
static uint32_t last_hold_log_scan = 0;
static uint32_t trunc_hold_count = 0;
static uint32_t last_resolved_target = 0;

static void on_term(int sig) {
    (void)sig;
    running = 0;
}

#define CPVC_DEBUG_MARKER "/tsd/var/carplayvc/debug-enabled"
static int cpvc_debug_on(void)
{
    static time_t checked;
    static int on;
    time_t now = time(0);
    if (!checked || now < checked || now - checked >= 5) {
        on = access(CPVC_DEBUG_MARKER, F_OK) == 0;
        checked = now ? now : 1;
    }
    return on;
}

static int log_essential(const char *tag, const char *msg) {
    return strcmp(tag, "boot") == 0 || strcmp(tag, "shutdown") == 0 ||
           strcmp(tag, "pid-lock") == 0 || strcmp(tag, "hardlink") == 0 ||
           strstr(msg, "errno") != NULL || strstr(msg, "fail") != NULL;
}

static void log_msg(const char *tag, const char *msg) {
    FILE *f;
    struct stat st;
    char old_path[96];
    int debug = cpvc_debug_on();
    const char *path = debug ? LOG_DEBUG : LOG_QUIET;
    long cap = debug ? LOG_CAP_DEBUG : LOG_CAP_QUIET;
    if (!debug && !log_essential(tag, msg)) return;
    if (!debug) mkdir(VAR_DIR, 0755);
    if (stat(path, &st) == 0 && st.st_size > cap) {
        snprintf(old_path, sizeof(old_path), "%s.1", path);
        rename(path, old_path);
    }
    f = fopen(path, "a");
    if (!f) {

        int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd >= 0) {
            f = fdopen(fd, "a");
            if (!f) { close(fd); return; }
        } else {
            return;
        }
    }
    fprintf(f, "[%ld] %s: %s\n", (long)time(NULL), tag, msg);
    fclose(f);
}

static void log_fmt(const char *tag, const char *fmt, ...) {
    char buf[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    log_msg(tag, buf);
}

static uint32_t fnv1a(const uint8_t *buf, size_t len) {
    uint32_t h = 0x811C9DC5u;
    size_t i;
    for (i = 0; i < len; i++) {
        h = (h ^ buf[i]) * 0x01000193u;
    }
    return h;
}

static int size_close(uint32_t a, uint32_t b) {
    uint32_t d = (a > b) ? (a - b) : (b - a);
    return d <= COVER_TARGET_TOL;
}

static int acquire_pid_lock(void) {
    int fd = open(PID_FILE, O_RDONLY);
    if (fd >= 0) {
        char buf[16];
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n > 0) {
            buf[n] = 0;
            int other_pid = atoi(buf);
            if (other_pid > 0 && kill(other_pid, 0) == 0) {

                log_fmt("pid-lock", "already running pid=%d, exit", other_pid);
                return -1;
            }
        }
    }

    fd = open(PID_FILE, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        log_fmt("pid-lock", "open pid-file failed errno=%d (continue without lock)", errno);
        return 0;
    }
    char buf[16];
    int n = snprintf(buf, sizeof(buf), "%d\n", (int)getpid());
    write(fd, buf, n);
    close(fd);
    return 0;
}

static void slot_free(int s) {
    if (s < 0 || s >= MAX_SLOTS) return;
    slots[s].id = -1;
    slots[s].state = SLOT_FREE;
    slots[s].expected_size = 0;
    slots[s].actual_size = 0;

}

static int slot_find(int id) {
    int s;
    for (s = 0; s < MAX_SLOTS; s++) {
        if (slots[s].state != SLOT_FREE && slots[s].id == id) return s;
    }
    return -1;
}

static int slot_acquire(int id) {

    int s = slot_find(id);
    if (s >= 0) return s;

    for (s = 0; s < MAX_SLOTS; s++) {
        if (slots[s].state == SLOT_FREE) {
            slots[s].id = id;
            slots[s].state = SLOT_SETUP;
            slots[s].expected_size = 0;
            slots[s].actual_size = 0;
            slots[s].last_seen = time(NULL);
            return s;
        }
    }

    int oldest = 0;
    time_t oldest_t = slots[0].last_seen;
    for (s = 1; s < MAX_SLOTS; s++) {
        if (slots[s].last_seen < oldest_t) {
            oldest_t = slots[s].last_seen;
            oldest = s;
        }
    }
    log_fmt("slot", "evict id=%d to acquire id=%d", slots[oldest].id, id);
    slot_free(oldest);
    slots[oldest].id = id;
    slots[oldest].state = SLOT_SETUP;
    slots[oldest].last_seen = time(NULL);
    return oldest;
}

static void slot_expire_stale(time_t now) {
    int s;
    for (s = 0; s < MAX_SLOTS; s++) {
        if (slots[s].state != SLOT_FREE
            && (now - slots[s].last_seen) > SLOT_IDLE_SECS) {
            log_fmt("slot", "expire-stale id=%d state=%d age=%lds",
                    slots[s].id, slots[s].state, (long)(now - slots[s].last_seen));
            slot_free(s);
        }
    }
}

static int parse_hex_at(const uint8_t *buf, size_t buflen, size_t off, size_t *out_end) {
    int val = 0;
    size_t i = off;
    int any = 0;
    while (i < buflen) {
        uint8_t c = buf[i];
        int d;
        if      (c >= '0' && c <= '9') d = c - '0';
        else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
        else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
        else break;
        val = val * 16 + d;
        i++;
        any = 1;
    }
    if (!any) return -1;
    if (out_end) *out_end = i;
    return val;
}

static int parse_hex_byte(uint8_t hi, uint8_t lo) {
    int hi_v = (hi >= '0' && hi <= '9') ? hi - '0' :
               (hi >= 'a' && hi <= 'f') ? hi - 'a' + 10 :
               (hi >= 'A' && hi <= 'F') ? hi - 'A' + 10 : -1;
    int lo_v = (lo >= '0' && lo <= '9') ? lo - '0' :
               (lo >= 'a' && lo <= 'f') ? lo - 'a' + 10 :
               (lo >= 'A' && lo <= 'F') ? lo - 'A' + 10 : -1;
    if (hi_v < 0 || lo_v < 0) return -1;
    return (hi_v << 4) | lo_v;
}

static size_t parse_data_block_safe(const uint8_t *buf, size_t buflen,
                                     size_t data_start, uint8_t *out, size_t max_out) {
    size_t i = data_start;
    size_t n = 0;
    int tid_race = 0;
    int tid_skips = 0;
    while (i + 1 < buflen && n < max_out) {

        while (i < buflen && (buf[i] == ' ' || buf[i] == '\t')) i++;
        if (i >= buflen) break;

        if (buf[i] == ']' || buf[i] == '\n' || buf[i] == '\r') break;

        if (buf[i] == 'T' && i + 4 < buflen && buf[i + 1] == 'I' && buf[i + 2] == 'D') {
            size_t j = i + 3, d = 0;
            while (j < buflen && buf[j] >= '0' && buf[j] <= '9' && d < 5) { j++; d++; }
            if (d >= 1 && j < buflen && buf[j] == ' ') {
                i = j + 1;
                tid_skips++;
                continue;
            }
        }

        if (i + 1 >= buflen) break;
        int b = parse_hex_byte(buf[i], buf[i + 1]);
        if (b < 0) break;
        out[n++] = (uint8_t)b;
        i += 2;

        if (i < buflen) {
            uint8_t c = buf[i];
            if (c != ' ' && c != ']' && c != '\n' && c != '\r' && c != '\t') {

                if (c == 'T' && i + 4 < buflen && buf[i + 1] == 'I' && buf[i + 2] == 'D') {
                    size_t j = i + 3, d = 0;
                    while (j < buflen && buf[j] >= '0' && buf[j] <= '9' && d < 5) { j++; d++; }
                    if (d >= 1 && j < buflen && buf[j] == ' ') {
                        i = j + 1;
                        tid_skips++;
                        continue;
                    }
                }
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                    tid_race = 1;
                    break;
                }
            }
        }
    }
    last_parse_tid_race = tid_race;
    if (tid_race) {

        log_fmt("parse", "tid-race detected after %lu bytes (%d tag-skips)",
                (unsigned long)n, tid_skips);
    } else if (tid_skips > 0) {
        log_fmt("parse", "recovered through %d TID tag-skips, %lu bytes",
                tid_skips, (unsigned long)n);
    }
    return n;
}

static uint32_t parse_setup_size(const uint8_t *buf, size_t buflen, size_t data_start) {
    uint8_t bytes[8];
    size_t n = parse_data_block_safe(buf, buflen, data_start, bytes, 8);
    if (n != 8) return 0;

    uint32_t hi = ((uint32_t)bytes[0] << 24) | ((uint32_t)bytes[1] << 16)
                | ((uint32_t)bytes[2] <<  8) |  (uint32_t)bytes[3];
    uint32_t lo = ((uint32_t)bytes[4] << 24) | ((uint32_t)bytes[5] << 16)
                | ((uint32_t)bytes[6] <<  8) |  (uint32_t)bytes[7];
    if (hi != 0) return 0;
    return lo;
}

static int find_jpeg_anchor(const uint8_t *data, size_t len) {
    size_t scan = (len > ANCHOR_SCAN) ? ANCHOR_SCAN : len;
    size_t i;
    for (i = 0; i + 3 < scan; i++) {
        if (data[i] == 0xFF && data[i + 1] == 0xD8
         && data[i + 2] == 0xFF
         && (data[i + 3] == 0xE0 || data[i + 3] == 0xE1)) {
            return (int)i;
        }
    }
    return -1;
}

static int find_first_after(const uint8_t *buf, size_t buflen, size_t start,
                            const char *tok, size_t toklen) {
    if (start + toklen > buflen) return -1;
    size_t i;
    for (i = start; i + toklen <= buflen; i++) {
        if (memcmp(buf + i, tok, toklen) == 0) return (int)i;
    }
    return -1;
}

static const char TOK_FILE_SETUP[] = "FILE[Setup id=";
static const char TOK_FILE_FIRST[] = "FILE[First id=";
static const char TOK_FILE_DATA[]  = "FILE[Data id=";
static const char TOK_FILE_LAST[]  = "FILE[Last id=";
static const char TOK_DATA[]       = " data=";

static int is_decapitated(const uint8_t *buf, size_t buflen, size_t off) {
    size_t i = off;
    while (i < buflen && (buf[i] == ' ' || buf[i] == '\t')) i++;
    if (i + 1 >= buflen) return 0;
    if (buf[i] == ']' || buf[i] == '\n' || buf[i] == '\r') return 0;
    if (parse_hex_byte(buf[i], buf[i + 1]) >= 0) return 0;
    return (buf[i] >= 'A' && buf[i] <= 'Z') || (buf[i] >= 'a' && buf[i] <= 'z');
}

static int line_opens_new_frame(const uint8_t *buf, size_t line_start, size_t line_end);

static size_t orphan_hex_start(const uint8_t *buf, size_t ls, size_t le) {
    size_t p = ls;

    if (p + 4 <= le && buf[p] == 'T' && buf[p+1] == 'I' && buf[p+2] == 'D') {
        size_t q = p + 3, d = 0;
        while (q < le && buf[q] >= '0' && buf[q] <= '9' && d < 5) { q++; d++; }
        if (d >= 1 && q < le && buf[q] == ' ') p = q + 1;
    }
    if (p + 4 < le
     && parse_hex_byte(buf[p], buf[p+1]) >= 0
     && buf[p+2] == ' '
     && parse_hex_byte(buf[p+3], buf[p+4]) >= 0) {
        return p;
    }
    return (size_t)-1;
}

static size_t parse_data_recovered(const uint8_t *buf, size_t buflen, size_t data_start,
                                   uint8_t *out, size_t max_out) {
    size_t n = parse_data_block_safe(buf, buflen, data_start, out, max_out);
    int raced = last_parse_tid_race;

    if (!raced && (n > 0 || !is_decapitated(buf, buflen, data_start))) {
        return n;
    }
    {
        size_t i = data_start;
        int line, appended = 0;
        while (i < buflen && buf[i] != '\n') i++;
        for (line = 0; line < 12 && i < buflen && n < max_out; line++) {
            size_t ls = i + 1;
            size_t le = ls;
            while (le < buflen && buf[le] != '\n') le++;
            if (line_opens_new_frame(buf, ls, le)) break;
            {
                size_t hs = orphan_hex_start(buf, ls, le);
                if (hs != (size_t)-1) {
                    size_t more = parse_data_block_safe(buf, buflen, hs, out + n, max_out - n);
                    n += more;
                    appended++;
                    break;
                }
            }
            i = le;
        }
        if (appended)
            log_fmt("recover", "%s -> stitched %d continuation line(s), %lu bytes total",
                    raced ? "tid-race" : "decapitated", appended, (unsigned long)n);
    }
    return n;
}

static int line_opens_new_frame(const uint8_t *buf, size_t line_start, size_t line_end) {
    size_t k;
    for (k = line_start; k + 5 <= line_end; k++)
        if (memcmp(buf + k, "FILE[", 5) == 0) return 1;
    return 0;
}

static size_t recover_first_orphan(const uint8_t *buf, size_t buflen, size_t off,
                                   uint8_t *out, size_t max_out) {
    size_t i = off;
    int line;
    for (line = 0; line < 4; line++) {
        size_t line_start = i;
        while (i < buflen && buf[i] != '\n') i++;

        if (line_opens_new_frame(buf, line_start, i)) return 0;
        if (i >= buflen) break;
        i++;
        if (i + 11 <= buflen
         && memcmp(buf + i, "FF D8 FF E", 10) == 0
         && (buf[i + 10] == '0' || buf[i + 10] == '1')) {
            return parse_data_block_safe(buf, buflen, i, out, max_out);
        }
    }
    return 0;
}

static size_t process_frame(const uint8_t *buf, size_t buflen, size_t off, time_t now) {

    int is_setup = (off + sizeof(TOK_FILE_SETUP) - 1 <= buflen)
                && memcmp(buf + off, TOK_FILE_SETUP, sizeof(TOK_FILE_SETUP) - 1) == 0;
    int is_first = !is_setup && (off + sizeof(TOK_FILE_FIRST) - 1 <= buflen)
                && memcmp(buf + off, TOK_FILE_FIRST, sizeof(TOK_FILE_FIRST) - 1) == 0;
    int is_data  = !is_setup && !is_first
                && (off + sizeof(TOK_FILE_DATA) - 1 <= buflen)
                && memcmp(buf + off, TOK_FILE_DATA, sizeof(TOK_FILE_DATA) - 1) == 0;
    int is_last  = !is_setup && !is_first && !is_data
                && (off + sizeof(TOK_FILE_LAST) - 1 <= buflen)
                && memcmp(buf + off, TOK_FILE_LAST, sizeof(TOK_FILE_LAST) - 1) == 0;
    if (!is_setup && !is_first && !is_data && !is_last) return 1;

    size_t tok_len = is_setup ? sizeof(TOK_FILE_SETUP) - 1
                   : is_first ? sizeof(TOK_FILE_FIRST) - 1
                   : is_data  ? sizeof(TOK_FILE_DATA)  - 1
                   :            sizeof(TOK_FILE_LAST)  - 1;

    size_t id_off = off + tok_len;
    size_t id_end = 0;
    int id = parse_hex_at(buf, buflen, id_off, &id_end);
    if (id < 0) return 1;

    int data_pos = find_first_after(buf, buflen, id_end, TOK_DATA, sizeof(TOK_DATA) - 1);
    if (data_pos < 0) return 1;
    size_t data_start = (size_t)data_pos + sizeof(TOK_DATA) - 1;

    if (is_setup) {
        int s = slot_acquire(id);
        if (s < 0) return 1;
        slots[s].expected_size = parse_setup_size(buf, buflen, data_start);
        slots[s].state = SLOT_SETUP;
        slots[s].actual_size = 0;
        slots[s].last_seen = now;

        if (slots[s].expected_size == 0) {
            slots[s].state = SLOT_TRUNCATED;
        } else if (slots[s].expected_size > MAX_JPEG_SIZE) {
            log_fmt("setup", "id=%d size=%u > MAX, mark truncated",
                    id, slots[s].expected_size);
            slots[s].state = SLOT_TRUNCATED;
        }
        return 1;
    }

    if (is_first) {
        int s = slot_find(id);
        if (s < 0) {

            s = slot_acquire(id);
            if (s < 0) return 1;
            slots[s].expected_size = 0;
        }

        uint8_t tmp[8192];
        size_t n = parse_data_block_safe(buf, buflen, data_start, tmp, sizeof(tmp));

        if (find_jpeg_anchor(tmp, n) < 0 && is_decapitated(buf, buflen, data_start)) {
            size_t rec = recover_first_orphan(buf, buflen, data_start, tmp, sizeof(tmp));
            if (rec >= 4) {
                n = rec;
                log_fmt("recover", "id=%d decapitated First -> recovered %lu bytes from anchor line",
                        id, (unsigned long)rec);
            }
        }
        if (n < 4) {
            slots[s].state = SLOT_CORRUPT_FIRST;
            slots[s].last_seen = now;
            return 1;
        }

        int anchor = find_jpeg_anchor(tmp, n);
        if (anchor < 0) {
            log_fmt("first", "id=%d no anchor in %lu bytes (TID-race or stale)",
                    id, (unsigned long)n);
            slots[s].state = SLOT_CORRUPT_FIRST;
            slots[s].last_seen = now;
            return 1;
        }

        size_t valid = n - (size_t)anchor;
        if (valid > MAX_JPEG_SIZE) valid = MAX_JPEG_SIZE;
        memcpy(slots[s].buf, tmp + anchor, valid);
        slots[s].actual_size = valid;
        slots[s].state = SLOT_FIRST_OK;
        slots[s].last_seen = now;
        if (anchor > 0) {
            log_fmt("first", "id=%d anchor-recovery offset=%d valid=%lu",
                    id, anchor, (unsigned long)valid);
        }
        return 1;
    }

    if (is_data) {
        int s = slot_find(id);
        if (s < 0) return 1;
        if (slots[s].state != SLOT_FIRST_OK && slots[s].state != SLOT_DATA) return 1;
        if (slots[s].actual_size >= MAX_JPEG_SIZE) {
            slots[s].state = SLOT_TRUNCATED;
            return 1;
        }
        size_t added = parse_data_recovered(buf, buflen, data_start,
                                              slots[s].buf + slots[s].actual_size,
                                              MAX_JPEG_SIZE - slots[s].actual_size);
        slots[s].actual_size += (uint32_t)added;
        slots[s].state = SLOT_DATA;
        slots[s].last_seen = now;
        return 1;
    }

    if (is_last) {
        int s = slot_find(id);
        if (s < 0) return 1;
        if (slots[s].state != SLOT_FIRST_OK && slots[s].state != SLOT_DATA) return 1;
        if (slots[s].actual_size < MAX_JPEG_SIZE) {
            size_t added = parse_data_recovered(buf, buflen, data_start,
                                                  slots[s].buf + slots[s].actual_size,
                                                  MAX_JPEG_SIZE - slots[s].actual_size);
            slots[s].actual_size += (uint32_t)added;
        }
        slots[s].last_seen = now;

        if (slots[s].actual_size < MIN_JPEG_SIZE) {
            slots[s].state = SLOT_TRUNCATED;
            return 1;
        }
        if (slots[s].buf[slots[s].actual_size - 2] != 0xFF
         || slots[s].buf[slots[s].actual_size - 1] != 0xD9) {
            log_fmt("last", "id=%d no FFD9 at end size=%u",
                    id, slots[s].actual_size);
            slots[s].state = SLOT_TRUNCATED;
            return 1;
        }

        if (slots[s].expected_size > 0) {
            uint32_t low  = slots[s].expected_size - (slots[s].expected_size / SETUP_TOLERANCE);
            uint32_t high = slots[s].expected_size + (slots[s].expected_size / SETUP_TOLERANCE);
            if (slots[s].actual_size < low || slots[s].actual_size > high) {
                log_fmt("last", "id=%d size mismatch actual=%u expected=%u",
                        id, slots[s].actual_size, slots[s].expected_size);
                slots[s].state = SLOT_TRUNCATED;
                return 1;
            }
        }
        slots[s].state = SLOT_COMPLETE;
        slots[s].complete_off = off;
        slots[s].complete_scan = scan_counter;
        return 1;
    }
    return 1;
}

#include <dirent.h>
static void cleanup_old_hardlinks(void) {
    DIR *d = opendir(VAR_DIR);
    if (!d) return;
    struct dirent *ent;
    typedef struct { char name[64]; time_t mtime; } LinkEntry;
    LinkEntry links[32];
    int count = 0;
    while ((ent = readdir(d)) != NULL && count < 32) {

        if (strncmp(ent->d_name, "cover-", 6) != 0) continue;
        const char *p = ent->d_name + 6;
        if (*p < '0' || *p > '9') continue;
        size_t nlen = strlen(ent->d_name);
        if (nlen < 11 || strcmp(ent->d_name + nlen - 4, ".jpg") != 0) continue;
        char full[160];
        snprintf(full, sizeof(full), "%s/%s", VAR_DIR, ent->d_name);
        struct stat st;
        if (stat(full, &st) < 0) continue;
        snprintf(links[count].name, sizeof(links[count].name), "%s", ent->d_name);
        links[count].mtime = st.st_mtime;
        count++;
    }
    closedir(d);

    int i, j;
    for (i = 0; i < count - 1; i++) {
        for (j = 0; j < count - 1 - i; j++) {
            if (links[j].mtime > links[j+1].mtime) {
                LinkEntry tmp = links[j]; links[j] = links[j+1]; links[j+1] = tmp;
            }
        }
    }

    if (count > HARDLINK_KEEP) {
        for (i = 0; i < count - HARDLINK_KEEP; i++) {
            char full[160];
            snprintf(full, sizeof(full), "%s/%s", VAR_DIR, links[i].name);
            unlink(full);
        }
    }
}

#define BOOT_CLEANUP_ORPHAN_MIN_AGE_S 600U

static void boot_cleanup(void) {
    char active_link[128];
    active_link[0] = '\0';
    {
        FILE *f = fopen(COVER_STATE, "r");
        if (f) {
            if (fgets(active_link, sizeof(active_link), f)) {
                size_t l = strlen(active_link);
                while (l > 0 && (active_link[l - 1] == '\n' ||
                                 active_link[l - 1] == '\r'))
                    active_link[--l] = '\0';
            }
            fclose(f);
        }
    }
    DIR *d = opendir(VAR_DIR);
    if (d) {
        struct dirent *ent;
        time_t now = time(NULL);
        while ((ent = readdir(d)) != NULL) {
            char full[160];
            size_t nlen = strlen(ent->d_name);
            struct stat st;
            if (nlen > 4 && strcmp(ent->d_name + nlen - 4, ".tmp") == 0) {
                snprintf(full, sizeof(full), "%s/%s", VAR_DIR, ent->d_name);
                unlink(full);
                continue;
            }
            if (strncmp(ent->d_name, "cover-", 6) != 0) continue;
            const char *p = ent->d_name + 6;
            if (*p < '0' || *p > '9') continue;
            if (nlen < 11 || strcmp(ent->d_name + nlen - 4, ".jpg") != 0) continue;
            snprintf(full, sizeof(full), "%s/%s", VAR_DIR, ent->d_name);
            if (active_link[0] && strcmp(full, active_link) == 0) continue;
            if (stat(full, &st) != 0) continue;
            if ((unsigned long)difftime(now, st.st_mtime) <
                (unsigned long)BOOT_CLEANUP_ORPHAN_MIN_AGE_S) continue;
            unlink(full);
        }
        closedir(d);
    }
}

static int write_hardlink_and_state(uint32_t mtime_sec) {
    char link_path[96];
    snprintf(link_path, sizeof(link_path), "%s%lu.jpg", COVER_LINK_PFX, (unsigned long)mtime_sec);

    unlink(link_path);
    if (link(COVER_DEST, link_path) < 0) {
        log_fmt("hardlink", "link errno=%d path=%s", errno, link_path);
        return -1;
    }

    int fd = open(COVER_STATE_TMP, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        log_fmt("state", "open errno=%d", errno);
        return -1;
    }
    size_t n = strlen(link_path);
    if (write(fd, link_path, n) != (ssize_t)n || write(fd, "\n", 1) != 1) {
        log_fmt("state", "write errno=%d", errno);
        close(fd);
        unlink(COVER_STATE_TMP);
        return -1;
    }
    if (fsync(fd) < 0) {

    }
    close(fd);
    if (rename(COVER_STATE_TMP, COVER_STATE) < 0) {
        log_fmt("state", "rename errno=%d", errno);
        return -1;
    }
    cleanup_old_hardlinks();
    log_fmt("hardlink", "ok path=%s", link_path);
    return 0;
}

static int write_cover_atomic(const uint8_t *data, size_t len) {
    int fd;
    mkdir(VAR_DIR, 0755);
    fd = open(COVER_TMP, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        log_fmt("write", "open-tmp errno=%d", errno);
        return -1;
    }
    ssize_t n = write(fd, data, len);
    if (n != (ssize_t)len) {
        log_fmt("write", "short write n=%ld errno=%d", (long)n, errno);
        close(fd);
        unlink(COVER_TMP);
        return -1;
    }

    if (fsync(fd) < 0) {
        log_fmt("write", "fsync warn errno=%d (continue)", errno);
    }
    close(fd);
    if (rename(COVER_TMP, COVER_DEST) < 0) {
        log_fmt("write", "rename errno=%d", errno);
        return -1;
    }

    write_hardlink_and_state((uint32_t)time(NULL));
    return 0;
}

static uint32_t read_target_size(void) {
    int fd = open(COVER_TARGET_SIZE, O_RDONLY);
    if (fd < 0) return 0;
    char b[32];
    ssize_t n = read(fd, b, sizeof(b) - 1);
    close(fd);
    if (n <= 0) return 0;
    b[n] = 0;
    long v = atol(b);
    if (v <= 0 || v > 16777216L) return 0;
    if (v > MAX_JPEG_SIZE) return (uint32_t)(MAX_JPEG_SIZE + 1);
    return (uint32_t)v;
}

static int last_state_nocover = 0;

static void write_nocover_state(uint32_t target) {
    last_resolved_target = target;
    if (last_state_nocover) return;
    int fd = open(COVER_STATE_TMP, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) { log_fmt("nocover", "open errno=%d", errno); return; }
    if (write(fd, "NOCOVER\n", 8) != 8) { close(fd); unlink(COVER_STATE_TMP); return; }
    if (fsync(fd) < 0) {  }
    close(fd);
    if (rename(COVER_STATE_TMP, COVER_STATE) < 0) { log_fmt("nocover", "rename errno=%d", errno); return; }
    last_state_nocover = 1;
    last_global_hash = 0;
    log_msg("nocover", "no exact cover for current track -> NOCOVER sentinel written");
}

static void scan_once(void) {
    int fd = open(IAP2_LOG, O_RDONLY);
    if (fd < 0) {
        log_fmt("read", "open errno=%d", errno);
        return;
    }
    off_t size = lseek(fd, 0, SEEK_END);
    if (size <= 0) {
        close(fd);
        log_fmt("read", "empty log size=%ld", (long)size);
        return;
    }
    off_t start = (size > SCAN_WINDOW) ? size - SCAN_WINDOW : 0;
    size_t buflen = (size_t)(size - start);
    uint8_t *buf = malloc(buflen);
    if (!buf) {
        close(fd);
        log_fmt("read", "malloc fail buflen=%lu", (unsigned long)buflen);
        return;
    }
    if (lseek(fd, start, SEEK_SET) < 0) {
        free(buf);
        close(fd);
        log_fmt("read", "seek errno=%d", errno);
        return;
    }
    ssize_t n = read(fd, buf, buflen);
    close(fd);
    if (n <= 0) {
        free(buf);
        log_fmt("read", "read errno=%d", errno);
        return;
    }

    time_t now = time(NULL);
    slot_expire_stale(now);

    size_t i = 0;
    uint32_t token_count = 0;
    while (i + 6 < (size_t)n) {
        if (buf[i] == 'F' && buf[i + 1] == 'I' && buf[i + 2] == 'L'
         && buf[i + 3] == 'E' && buf[i + 4] == '[') {
            token_count++;
            size_t adv = process_frame(buf, (size_t)n, i, now);
            i += adv;
        } else {
            i++;
        }
    }

    log_fmt("diag", "scan_n=%u buflen=%lu tokens=%u",
            scan_counter, (unsigned long)n, token_count);

    if (token_count > 0) trunc_hold_count = 0;

    uint32_t target = read_target_size();
    int best = -1;
    int s;

    if (target != last_logged_target) {
        log_fmt("target", "changed %u -> %u scan=%u", last_logged_target, target, scan_counter);
        last_logged_target = target;
        last_recovery_scan = scan_counter;
        no_match_scan_count = 0;
        if (tap_mode == MODE_IDLE) {
            tap_mode = MODE_ACTIVE;
            log_fmt("wake", "target changed -> ACTIVE");
        }
        last_target_seen = target;
    }

    if (target > 0) {

        if (target <= MAX_JPEG_SIZE) {
            for (s = 0; s < MAX_SLOTS; s++) {
                if (slots[s].state == SLOT_COMPLETE
                 && slots[s].complete_scan + COMPLETE_LOOKBACK_SCANS >= scan_counter
                 && slots[s].actual_size >= MIN_JPEG_SIZE
                 && size_close(slots[s].expected_size, target)) {
                    if (best < 0
                     || slots[s].complete_scan > slots[best].complete_scan
                     || (slots[s].complete_scan == slots[best].complete_scan
                         && slots[s].complete_off > slots[best].complete_off)) best = s;
                }
            }
        }

        if (best < 0 && target <= MAX_JPEG_SIZE
         && (scan_counter - last_recovery_scan) >= (uint32_t)RECOVERY_INTERVAL_SCANS) {
            last_recovery_scan = scan_counter;
            for (s = 0; s < MAX_SLOTS; s++) {
                if (slots[s].state == SLOT_COMPLETE
                 && slots[s].actual_size >= MIN_JPEG_SIZE
                 && size_close(slots[s].expected_size, target)) {

                    if (best < 0
                     || slots[s].complete_scan > slots[best].complete_scan
                     || (slots[s].complete_scan == slots[best].complete_scan
                         && slots[s].complete_off > slots[best].complete_off)) best = s;
                }
            }
            if (best >= 0)
                log_fmt("pick", "recovery-match id=%d size=%u scan=%u (retry, aged blob)",
                        slots[best].id, slots[best].actual_size, scan_counter);
            else
                log_fmt("recovery", "no aged blob for target=%u scan=%u (retry ~%ds)",
                        target, scan_counter, RECOVERY_INTERVAL_SCANS * POLL_INTERVAL);
        }
        if (best >= 0) {
            if (slots[best].actual_size != slots[best].expected_size) {
                log_fmt(RELAXED_PICK_LOG_TAG,
                        "id=%d size=%u expected=%u target=%u (chunk lost to tracer, shipping)",
                        slots[best].id, slots[best].actual_size,
                        slots[best].expected_size, target);
            } else if (slots[best].complete_scan != scan_counter) {

                log_fmt("pick", "late-match id=%d size=%u completed_scan=%u now=%u (aged)",
                        slots[best].id, slots[best].actual_size,
                        slots[best].complete_scan, scan_counter);
            } else {
                log_fmt("pick", "size-match id=%d size=%u off=%lu scan=%u",
                        slots[best].id, slots[best].actual_size,
                        (unsigned long)slots[best].complete_off, scan_counter);
            }
        } else {
            log_fmt("pick", "no exact match for target=%u -> no-cover bridge", target);
        }
    } else {
        for (s = 0; s < MAX_SLOTS; s++) {
            if (slots[s].state == SLOT_COMPLETE
             && slots[s].complete_scan == scan_counter
             && slots[s].actual_size >= MIN_JPEG_SIZE) {
                if (best < 0 || slots[s].complete_off > slots[best].complete_off) best = s;
            }
        }
        if (best >= 0)
            log_fmt("pick", "offset id=%d off=%lu size=%u scan=%u (no target)",
                    slots[best].id, (unsigned long)slots[best].complete_off,
                    slots[best].actual_size, scan_counter);
    }
    if (best < 0) {
        int truncated = (last_global_hash != 0 && token_count == 0
                         && (size_t)n < COVER_TRUNC_BUFLEN);
        if (truncated && trunc_hold_count < MAX_TRUNC_HOLD_SCANS) {
            trunc_hold_count++;
            if (no_match_scan_count == 0
             || (scan_counter - last_hold_log_scan) >= (uint32_t)ALIVE_LOG_EVERY) {
                log_fmt("hold", "truncated read buflen=%lu tokens=0 target=%u -> keep last cover (%u/%u)",
                        (unsigned long)n, target, trunc_hold_count, (uint32_t)MAX_TRUNC_HOLD_SCANS);
                last_hold_log_scan = scan_counter;
            }
            free(buf);
            return;
        }
        if (truncated) {
            log_fmt("hold", "truncation budget exhausted (%u) -> release to no-cover", trunc_hold_count);
            write_nocover_state(target);
            free(buf);
            return;
        }
        if (target > MAX_JPEG_SIZE) {

            write_nocover_state(target);
        } else {
            no_match_scan_count++;
            if (target != last_resolved_target
             && no_match_scan_count >= (uint32_t)NOCOVER_GRACE_SCANS) {
                log_fmt("nocover", "new target %u unmatched after %u scans -> NOCOVER",
                        target, no_match_scan_count);
                write_nocover_state(target);
                no_match_scan_count = 0;
            } else if (no_match_scan_count == 1 || (scan_counter - last_hold_log_scan) >= (uint32_t)ALIVE_LOG_EVERY) {
                int cc = 0; int s2;
                for (s2 = 0; s2 < MAX_SLOTS; s2++)
                    if (slots[s2].state == SLOT_COMPLETE) cc++;
                log_fmt("scan", "no exact match (target=%u) -> hold [%d COMPLETE, nocover=%d, miss#=%u]",
                        target, cc, last_state_nocover, no_match_scan_count);
                last_hold_log_scan = scan_counter;
            }
        }
        free(buf);
        return;
    }

    uint32_t h = fnv1a(slots[best].buf, slots[best].actual_size);
    if (h == last_global_hash) {

        slot_free(best);
        free(buf);
        return;
    }
    if (write_cover_atomic(slots[best].buf, slots[best].actual_size) == 0) {
        last_global_hash = h;
        last_state_nocover = 0;
        last_resolved_target = target;
        no_match_scan_count = 0;
        log_fmt("write", "id=%d size=%u setup=%u hash=%08lx",
                slots[best].id, slots[best].actual_size,
                slots[best].expected_size, (unsigned long)h);
        if (tap_mode == MODE_ACTIVE) {
            tap_mode = MODE_IDLE;
            idle_full_scan_counter = 0;
            log_fmt("sleep", "cover written -> IDLE");
        }
    }
    slot_free(best);
    free(buf);
}

int main(int argc, char **argv) {
    (void)argc; (void)argv;

    JVM_LIKE_START = time(NULL);

    mkdir(VAR_DIR, 0755);

    signal(SIGTERM, on_term);
    signal(SIGINT,  on_term);

    if (acquire_pid_lock() < 0) {
        return 0;
    }

    boot_cleanup();

    int s;
    for (s = 0; s < MAX_SLOTS; s++) {
        slots[s].id = -1;
        slots[s].state = SLOT_FREE;
        slots[s].complete_scan = 0;
        slots[s].complete_off = 0;
    }

    log_msg("boot", "cpvc-cover-bridge v2 starting");

    int waited = 0;
    while (running && waited < BOOT_DELAY) {
        sleep(1);
        waited++;
    }
    if (!running) return 0;

    log_msg("loop", "entering scan loop (ACTIVE 2s / IDLE 1s+10s verify)");

    while (running) {
        if (tap_mode == MODE_IDLE) {
            uint32_t t = read_target_size();
            if (t != last_target_seen) {
                tap_mode = MODE_ACTIVE;
                log_fmt("wake", "target %u -> %u in IDLE -> ACTIVE", last_target_seen, t);
            } else {
                idle_full_scan_counter++;
                if (idle_full_scan_counter >= (uint32_t)IDLE_FULL_SCAN_EVERY) {
                    idle_full_scan_counter = 0;
                    scan_counter++;
                    scan_once();
                }
                if (tap_mode == MODE_IDLE) {
                    sleep(IDLE_CHECK_INTERVAL);
                }
                continue;
            }
        }
        scan_counter++;
        scan_once();
        if (scan_counter % ALIVE_LOG_EVERY == 0) {
            log_fmt("alive", "scan_n=%u mode=%s uptime=%lds", scan_counter,
                    tap_mode == MODE_IDLE ? "IDLE" : "ACTIVE",
                    (long)(time(NULL) - JVM_LIKE_START));
        }
        int slept = 0;
        while (running && slept < POLL_INTERVAL && tap_mode == MODE_ACTIVE) {
            sleep(1);
            slept++;
        }
    }

    log_msg("shutdown", "cpvc-cover-bridge exiting");
    unlink(PID_FILE);
    return 0;
}
