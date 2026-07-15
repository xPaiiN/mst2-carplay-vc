/*
 * MST2 CarPlay Cover Live-Capture.
 *
 * Reads the stock iAP2 trace /dev/shmem/iap2_carplay.log, reassembles the cover JPEG sent over the
 * iAP2 file-transfer protocol (8-slot state machine) and writes it track-bound to
 * /tsd/var/carplayvc/cover.jpg + a unique hardlink cover-<mtime>.jpg (path in cover-current.path,
 * for the picserver cache-bust). The Bridge places the target cover size (PPS MediaItemArtworkSize)
 * in cover-target-size; the tap shows ONLY the COMPLETE blob whose Setup-declared size AND reassembled
 * size both equal that target (exact match = byte-accurate, current track). No tolerance/stale
 * fallback: a non-exact blob is never shown (it used to admit a size-close FOREIGN cover -> the
 * wrong-cover bug). No exact cover for the current track -> the "NOCOVER" sentinel goes into
 * cover-current.path (K3 no-cover bridge: CoverArt pushes stock no-cover instead of the old cover).
 * K2 decapitation recovery (parse_data_recovered) recovers the ~4 KB packet that the stock logger's
 * thread-interleave occasionally bleeds out of a Data OR First frame's header (First via
 * recover_first_orphan, anchor-at-column-0), so the correct cover reaches its exact size instead of
 * falling ~one packet short (or losing its JPEG anchor). Anti-flicker via FNV-hash dedupe.
 *
 * Trace frame format (ASCII):
 *   FILE[Setup id=N data=<8 byte big-endian size>]   (size == PPS MediaItemArtworkSize)
 *   FILE[First id=N data=FF D8 FF E0 ...]             (JFIF SOI anchor within the first 64 bytes)
 *   FILE[Data  id=N data=<hex bytes>] ...
 *   FILE[Last  id=N data=... FF D9]                   (EOI; Setup size validated +/-10%)
 * The id is a small Apple counter that wraps back to a single digit at ~90; the "newest" cover is
 * therefore the COMPLETE slot with the largest last-frame offset in the scan buffer, NOT the highest
 * id. complete_scan==scan_counter keeps this wrap- and re-scan-immune. 8 logger threads write
 * interleaved -> TID-race hex validator + first-frame anchor filter out mangled frames.
 *
 * QNX 6.5 ARMv7 EABI ELF, deployed to /tsd/lib/carplayvc/cpvc-cover-tap, started via the runHMI.sh
 * keepalive spawn, terminates on SIGTERM/SIGINT. Single-instance via PID flock.
 * BRICK-RISK minimal: only reads from /dev/shmem + writes under /tsd/var/carplayvc/.
 * MEMORY: 8 slots * 320 KB BSS = 2.5 MB + 3 MB malloc/scan (freed after scan_once); ~200 MB free.
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
#define COVER_DEST      "/tsd/var/carplayvc/cover.jpg"
#define COVER_TMP       "/tsd/var/carplayvc/cover.jpg.tmp"
#define LOG_FILE        "/tsd/var/carplayvc/cpvc-cover-tap.log"
#define PID_FILE        "/tsd/var/carplayvc/cpvc-cover-tap.pid"
#define VAR_DIR         "/tsd/var/carplayvc"
#define COVER_STATE     "/tsd/var/carplayvc/cover-current.path"   /* state file with the current hardlink path */
#define COVER_STATE_TMP "/tsd/var/carplayvc/cover-current.path.tmp"
#define COVER_TARGET_SIZE "/tsd/var/carplayvc/cover-target-size"   /* PPS MediaItemArtworkSize, written atomically by the Bridge */
#define COVER_LINK_PFX  "/tsd/var/carplayvc/cover-"               /* hardlink prefix; full name = cover-<mtime>.jpg */
#define HARDLINK_KEEP   5                                          /* keep last-N hardlinks (back-skip headroom) */

#define SCAN_WINDOW    (3072 * 1024)   /* 3 MB: stock trace runs up to ~1.97 MB; 3 MB headroom */
#define MIN_JPEG_SIZE    1024          /* < 1 KB = Garbage / Thumbnail-Reject */
#define MAX_JPEG_SIZE   (320 * 1024)   /* Apple covers occupy up to ~186 KB; headroom lets larger covers count as COMPLETE instead of TRUNCATED. 8 slots = 2.5 MB BSS */
#define POLL_INTERVAL    2             /* 2 s between scans */
#define COMPLETE_LOOKBACK_SCANS 4      /* t67: exact-match pick accepts a slot that became COMPLETE within
                                        * the last N scans (~8 s), not only THIS scan. Fixes the B2 miss:
                                        * a cover that completed in the trace DURING the Bridge's 2 s
                                        * debounce (before cover-target-size was written) is otherwise
                                        * dropped forever by the complete_scan==scan_counter gate. Safety
                                        * still rests on the exact double-size match, not on this gate. */
#define RECOVERY_INTERVAL_SCANS 4      /* v3: when a real target has shown NOCOVER for ~this many scans
                                        * (~8 s), retry the exact size-match WITHOUT the LOOKBACK bound --
                                        * the cover blob may sit in a slot but have aged past the window
                                        * (fast skips / long debounce). Still exact double-size match, so
                                        * no wrong-cover; only relaxes the recency gate. Repeats every
                                        * interval until a cover shows or the target changes. */
#define BOOT_DELAY      10             /* 10 s after sidecar boot for HMI/iAP2 ready */
#define ALIVE_LOG_EVERY 60             /* keep-alive entry every 60 scans */
#define MAX_SLOTS        8             /* 8 parallel file IDs; Apple typically sends 1-2 */
#define SLOT_IDLE_SECS  20             /* free stale slots faster (stuck-fix hygiene) */
#define ANCHOR_SCAN     64             /* scan the first 64 bytes of the First-frame data= for FF D8 FF E0/E1 */
#define SETUP_TOLERANCE 10             /* Setup size accepted +/-10%; otherwise TRUNCATED (safety net: a wrong K2 recovery lands outside this band -> rejected, never shown) */

typedef enum {
    SLOT_FREE = 0,
    SLOT_SETUP,         /* Setup frame received, size known, waiting for First */
    SLOT_FIRST_OK,      /* First frame OK, anchor found, data possibly collected */
    SLOT_DATA,          /* at least one Data frame collected */
    SLOT_COMPLETE,      /* Last frame OK, JPEG complete, FFD9 verified */
    SLOT_TRUNCATED,     /* validation fail (size mismatch, TID-race, missing FFD9) */
    SLOT_CORRUPT_FIRST  /* First frame without FF D8 FF E0/E1 anchor */
} SlotState;

typedef struct {
    int        id;              /* iAP2 file-id; -1 = SLOT_FREE */
    SlotState  state;
    uint32_t   expected_size;   /* from FILE[Setup] 8-byte BE */
    uint32_t   actual_size;     /* reassembled so far */
    uint32_t   write_hash;      /* FNV-1a of the last written variant */
    time_t     last_seen;       /* recycle on staleness */
    uint32_t   complete_scan;   /* scan_counter in which this slot last became COMPLETE */
    size_t     complete_off;    /* buffer offset of the Last frame (= send order) */
    uint8_t    buf[MAX_JPEG_SIZE];
} File;

static File      slots[MAX_SLOTS];
static volatile int running = 1;
static uint32_t  last_global_hash = 0;  /* cross-slot dedupe protection */
static uint32_t  scan_counter = 0;      /* total scans since boot */
static time_t    JVM_LIKE_START = 0;    /* set in main() for the alive log */
static uint32_t  last_logged_target = 0;/* last seen target size -> change-detection log */
static uint32_t  last_recovery_scan = 0;/* v3: scan of the last 7s-recovery attempt (reset on target change) */

static void on_term(int sig) {
    (void)sig;
    running = 0;
}

static void log_msg(const char *tag, const char *msg) {
    FILE *f = fopen(LOG_FILE, "a");
    if (!f) {
        /* logger fail-safe: single O_TRUNC retry against stale-fd / lock */
        int fd = open(LOG_FILE, O_WRONLY | O_CREAT | O_TRUNC, 0644);
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

/* FNV-1a 32-bit hash for change detection */
static uint32_t fnv1a(const uint8_t *buf, size_t len) {
    uint32_t h = 0x811C9DC5u;
    size_t i;
    for (i = 0; i < len; i++) {
        h = (h ^ buf[i]) * 0x01000193u;
    }
    return h;
}

/* PID-flock single-instance check. If the pid-file exists and the process is still
 * running, this instance should exit. Otherwise overwrite the pid-file with our own PID. */
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
                /* Other instance is alive -- abort */
                log_fmt("pid-lock", "already running pid=%d, exit", other_pid);
                return -1;
            }
        }
    }
    /* Write own pid */
    fd = open(PID_FILE, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        log_fmt("pid-lock", "open pid-file failed errno=%d (continue without lock)", errno);
        return 0;  /* non-fatal; sidecar continues */
    }
    char buf[16];
    int n = snprintf(buf, sizeof(buf), "%d\n", (int)getpid());
    write(fd, buf, n);
    close(fd);
    return 0;
}

/* Slot management */
static void slot_free(int s) {
    if (s < 0 || s >= MAX_SLOTS) return;
    slots[s].id = -1;
    slots[s].state = SLOT_FREE;
    slots[s].expected_size = 0;
    slots[s].actual_size = 0;
    /* write_hash is retained -- dedupe protection even on slot reuse */
}

static int slot_find(int id) {
    int s;
    for (s = 0; s < MAX_SLOTS; s++) {
        if (slots[s].state != SLOT_FREE && slots[s].id == id) return s;
    }
    return -1;
}

static int slot_acquire(int id) {
    /* already exists? re-use */
    int s = slot_find(id);
    if (s >= 0) return s;
    /* find a free one */
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
    /* full -> evict the oldest */
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

/* Parse an ASCII-digit integer from offset; returns -1 if none, otherwise the value */
static int parse_uint_at(const uint8_t *buf, size_t buflen, size_t off, size_t *out_end) {
    int val = 0;
    size_t i = off;
    int any = 0;
    while (i < buflen && buf[i] >= '0' && buf[i] <= '9') {
        val = val * 10 + (buf[i] - '0');
        i++;
        any = 1;
    }
    if (!any) return -1;
    if (out_end) *out_end = i;
    return val;
}

/* Parse 1 hex byte (2 chars); returns -1 on failure */
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

/* Parse space-separated UPPERCASE hex bytes from data_start up to ']' or a TID-race.
 * TID-race detector: peek after each hex byte. If the next char is not
 * (space|]|newline|tab|CR) AND alphabetic, then it is a TID bleed like
 * "FF D8 TID09 04" -- stop frame parsing.
 * Returns the number of decoded bytes, writes into out (max max_out). */
static size_t parse_data_block_safe(const uint8_t *buf, size_t buflen,
                                     size_t data_start, uint8_t *out, size_t max_out) {
    size_t i = data_start;
    size_t n = 0;
    int tid_race = 0;
    while (i + 1 < buflen && n < max_out) {
        /* Skip leading whitespace */
        while (i < buflen && (buf[i] == ' ' || buf[i] == '\t')) i++;
        if (i >= buflen) break;
        /* End-of-block markers */
        if (buf[i] == ']' || buf[i] == '\n' || buf[i] == '\r') break;
        /* Must be hex-pair */
        if (i + 1 >= buflen) break;
        int b = parse_hex_byte(buf[i], buf[i + 1]);
        if (b < 0) break;
        out[n++] = (uint8_t)b;
        i += 2;
        /* TID-race validator: peek after the hex pair for a letter */
        if (i < buflen) {
            uint8_t c = buf[i];
            if (c != ' ' && c != ']' && c != '\n' && c != '\r' && c != '\t') {
                /* expects space or ] here. If a letter -> TID bleed */
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                    tid_race = 1;
                    break;
                }
            }
        }
    }
    if (tid_race) {
        /* the caller cannot tell from the return value whether it was a TID-race or a normal end.
         * We log it separately for forensics. */
        log_fmt("parse", "tid-race detected after %lu bytes", (unsigned long)n);
    }
    return n;
}

/* Setup frame parses 8 hex bytes big-endian as uint64, truncates to uint32.
 * Called only for FILE[Setup id=N data=HH HH HH HH HH HH HH HH] */
static uint32_t parse_setup_size(const uint8_t *buf, size_t buflen, size_t data_start) {
    uint8_t bytes[8];
    size_t n = parse_data_block_safe(buf, buflen, data_start, bytes, 8);
    if (n != 8) return 0;
    /* big-endian uint64 -> uint32 truncate (top 32 bits are always 0 for Apple sizes) */
    uint32_t hi = ((uint32_t)bytes[0] << 24) | ((uint32_t)bytes[1] << 16)
                | ((uint32_t)bytes[2] <<  8) |  (uint32_t)bytes[3];
    uint32_t lo = ((uint32_t)bytes[4] << 24) | ((uint32_t)bytes[5] << 16)
                | ((uint32_t)bytes[6] <<  8) |  (uint32_t)bytes[7];
    if (hi != 0) return 0;  /* unrealistic for a cover JPEG */
    return lo;
}

/* Anchor scan First frame: expects FF D8 FF (E0|E1) within the first 64 bytes.
 * Returns the offset or -1 if not found. */
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

/* Finds the first occurrence of tok from start */
static int find_first_after(const uint8_t *buf, size_t buflen, size_t start,
                            const char *tok, size_t toklen) {
    if (start + toklen > buflen) return -1;
    size_t i;
    for (i = start; i + toklen <= buflen; i++) {
        if (memcmp(buf + i, tok, toklen) == 0) return (int)i;
    }
    return -1;
}

/* Tokens */
static const char TOK_FILE_SETUP[] = "FILE[Setup id=";
static const char TOK_FILE_FIRST[] = "FILE[First id=";
static const char TOK_FILE_DATA[]  = "FILE[Data id=";    /* corrected token (was "FILE[Middle id=") */
static const char TOK_FILE_LAST[]  = "FILE[Last id=";
static const char TOK_DATA[]       = " data=";

/* K2 -- decapitation recovery.
 * The stock iAP2 logger interleaves 8 threads into one shmem trace. Occasionally a thread blends its
 * text (e.g. "TID04 Received ACK/response") directly into another thread's "FILE[Data id=N data=" --
 * the ~4 KB hex payload is "decapitated" from its header and lands as a headerless hex line
 * immediately after. The normal FILE[ token scan ignores headerless hex -> that one packet is lost ->
 * the cover reassembles ~one packet short of its Setup size (vehicle-observed: id=83 declared 153458,
 * reassembled 149374 = exactly one 4084 B packet short). These two helpers recover it.
 * Mis-attribution is harmless: the downstream Setup-size +/-10% + FFD9 validation rejects a wrongly
 * recovered blob as TRUNCATED -> with the exact-match pick it is never shown. */

/* data= is decapitated: at off (after optional spaces/tabs) sits a letter that does NOT start a valid
 * hex pair -> the payload was bled away from the header (a real cover byte is always hex). */
static int is_decapitated(const uint8_t *buf, size_t buflen, size_t off) {
    size_t i = off;
    while (i < buflen && (buf[i] == ' ' || buf[i] == '\t')) i++;
    if (i + 1 >= buflen) return 0;
    if (buf[i] == ']' || buf[i] == '\n' || buf[i] == '\r') return 0;
    if (parse_hex_byte(buf[i], buf[i + 1]) >= 0) return 0;   /* valid hex -> not decapitated */
    return (buf[i] >= 'A' && buf[i] <= 'Z') || (buf[i] >= 'a' && buf[i] <= 'z');
}

/* Parse a data block; on a decapitated header, recover the orphaned hex continuation from the next
 * line. Returns total decoded bytes written to out (<= max_out). Normal (non-decapitated) frames go
 * through parse_data_block_safe unchanged. */
static size_t parse_data_recovered(const uint8_t *buf, size_t buflen, size_t data_start,
                                   uint8_t *out, size_t max_out) {
    size_t n = parse_data_block_safe(buf, buflen, data_start, out, max_out);
    if (n == 0 && is_decapitated(buf, buflen, data_start)) {
        size_t i = data_start;
        while (i < buflen && buf[i] != '\n') i++;          /* skip the bled header line */
        if (i < buflen) {
            size_t cont = i + 1;
            /* guard: the continuation must start with naked hex "HH HH" -> defends against picking up
             * a plain log line ("Start the timer...") instead of the real orphaned payload. */
            if (cont + 4 < buflen
             && parse_hex_byte(buf[cont], buf[cont + 1]) >= 0
             && buf[cont + 2] == ' '
             && parse_hex_byte(buf[cont + 3], buf[cont + 4]) >= 0) {
                n = parse_data_block_safe(buf, buflen, cont, out, max_out);
                log_fmt("recover", "decapitated frame -> recovered %lu bytes from orphan line",
                        (unsigned long)n);
            }
        }
    }
    return n;
}

/* H-A -- decapitated First-frame recovery. The First frame carries the JPEG anchor (FF D8 FF E0/E1);
 * when its header is bled away the payload lands headerless on a following line -- and unlike a Data
 * decapitation this can be TWO bleed lines down (vehicle-observed: "...data=Removed packet" /
 * "TID04 Received ACK/response" / "FF D8 FF E0 ..."). We take the next line whose hex text begins with
 * the anchor AT COLUMN 0 (right after a newline). Safety does NOT rest on the anchor being unique
 * (FF D8 is generic) but on this column-0 constraint: a healthy foreign First has "...data=FF D8..."
 * mid-line (never col 0), a Data orphan never starts with FF D8 FF E0/E1 -> only a decapitated First's
 * orphaned payload qualifies. The scan also stops at the next "FILE[" frame (defence-in-depth against
 * two simultaneously decapitated Firsts), and a wrong pick would still fail the downstream Setup-size +
 * FFD9 + exact-match gate -> no-cover, never wrong. Returns decoded bytes (anchor at offset 0), 0 else. */
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
        while (i < buflen && buf[i] != '\n') i++;     /* to end of current line */
        /* a new FILE[ frame opened on this line -> the orphan cannot be past it; stop */
        if (line_opens_new_frame(buf, line_start, i)) return 0;
        if (i >= buflen) break;
        i++;                                           /* start of the next line */
        if (i + 11 <= buflen
         && memcmp(buf + i, "FF D8 FF E", 10) == 0
         && (buf[i + 10] == '0' || buf[i + 10] == '1')) {
            return parse_data_block_safe(buf, buflen, i, out, max_out);
        }
    }
    return 0;
}

/* Process one FILE[<state>] frame found at offset. Returns advance-bytes. */
static size_t process_frame(const uint8_t *buf, size_t buflen, size_t off, time_t now) {
    /* determine the frame type */
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

    /* parse the id */
    size_t id_off = off + tok_len;
    size_t id_end = 0;
    int id = parse_uint_at(buf, buflen, id_off, &id_end);
    if (id < 0) return 1;

    /* find data= */
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
        /* Setup-size 0 -> 0-byte sentinel (id=80 FirstLast-Pattern) -- ignore */
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
            /* First without Setup -- accept with unknown size */
            s = slot_acquire(id);
            if (s < 0) return 1;
            slots[s].expected_size = 0;
        }
        /* Parse data in temp buffer */
        uint8_t tmp[8192];
        size_t n = parse_data_block_safe(buf, buflen, data_start, tmp, sizeof(tmp));
        /* H-A: a decapitated First (data= bled away to a TID text) has no anchor here -> recover the
         * orphaned anchor line that holds the real First payload (vehicle: id=88 "Crown" was a no-cover
         * because of exactly this). Anchor-validated, so safe. */
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
        /* search for the FF D8 FF E0/E1 anchor */
        int anchor = find_jpeg_anchor(tmp, n);
        if (anchor < 0) {
            log_fmt("first", "id=%d no anchor in %lu bytes (TID-race or stale)",
                    id, (unsigned long)n);
            slots[s].state = SLOT_CORRUPT_FIRST;
            slots[s].last_seen = now;
            return 1;
        }
        /* Copy from anchor offset */
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
        /* Validate EOI */
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
        /* Validate Setup-Size +/- 10% */
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
        slots[s].complete_off = off;            /* position of the Last frame in the scan buffer */
        slots[s].complete_scan = scan_counter;  /* only counts if completed in THIS scan */
        return 1;
    }
    return 1;
}

/* Clean up old hardlinks (keep_last) -- scans VAR_DIR, sorts by mtime, unlinks the oldest.
 * Quick bubble sort for embedded; at most MAX_SLOTS hardlinks expected, low cost. */
#include <dirent.h>
static void cleanup_old_hardlinks(uint32_t current_mtime_sec) {
    DIR *d = opendir(VAR_DIR);
    if (!d) return;
    struct dirent *ent;
    typedef struct { char name[64]; time_t mtime; } LinkEntry;
    LinkEntry links[32];
    int count = 0;
    while ((ent = readdir(d)) != NULL && count < 32) {
        /* expect cover-<digits>.jpg */
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
    /* Bubble-sort by mtime ascending (oldest first) */
    int i, j;
    for (i = 0; i < count - 1; i++) {
        for (j = 0; j < count - 1 - i; j++) {
            if (links[j].mtime > links[j+1].mtime) {
                LinkEntry tmp = links[j]; links[j] = links[j+1]; links[j+1] = tmp;
            }
        }
    }
    /* unlink all but the last HARDLINK_KEEP */
    if (count > HARDLINK_KEEP) {
        for (i = 0; i < count - HARDLINK_KEEP; i++) {
            char full[160];
            snprintf(full, sizeof(full), "%s/%s", VAR_DIR, links[i].name);
            unlink(full);
        }
    }
    (void)current_mtime_sec;
}

/* Boot cleanup: remove all cover artifacts of a previous session so the markerless
 * CoverArt shadow does not push a stale cover after reboot (it reads cover-current.path). Only the
 * winning single instance calls this after the PID lock. */
static void boot_cleanup(void) {
    DIR *d = opendir(VAR_DIR);
    if (d) {
        struct dirent *ent;
        while ((ent = readdir(d)) != NULL) {
            if (strncmp(ent->d_name, "cover-", 6) != 0) continue;
            const char *p = ent->d_name + 6;
            if (*p < '0' || *p > '9') continue;
            size_t nlen = strlen(ent->d_name);
            if (nlen < 11 || strcmp(ent->d_name + nlen - 4, ".jpg") != 0) continue;
            char full[160];
            snprintf(full, sizeof(full), "%s/%s", VAR_DIR, ent->d_name);
            unlink(full);
        }
        closedir(d);
    }
    unlink(COVER_DEST);
    unlink(COVER_TMP);
    unlink(COVER_STATE);
    unlink(COVER_STATE_TMP);
    unlink(COVER_TARGET_SIZE);   /* a stale real target from a prior session could otherwise admit a
                                  * size-collision cover for a few seconds until the Bridge rewrites it */
}

/* hardlink with unique path + state-file write (the Bridge reads cover-current.path) */
static int write_hardlink_and_state(uint32_t mtime_sec) {
    char link_path[96];
    snprintf(link_path, sizeof(link_path), "%s%lu.jpg", COVER_LINK_PFX, (unsigned long)mtime_sec);
    /* defensive: remove an old hardlink with the same name */
    unlink(link_path);
    if (link(COVER_DEST, link_path) < 0) {
        log_fmt("hardlink", "link errno=%d path=%s", errno, link_path);
        return -1;
    }
    /* Atomic state-file write */
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
        /* not fatal */
    }
    close(fd);
    if (rename(COVER_STATE_TMP, COVER_STATE) < 0) {
        log_fmt("state", "rename errno=%d", errno);
        return -1;
    }
    cleanup_old_hardlinks(mtime_sec);
    log_fmt("hardlink", "ok path=%s", link_path);
    return 0;
}

/* atomic write with fsync before rename (POSIX atomicity protection) + hardlink with unique path */
static int write_cover_atomic(const uint8_t *data, size_t len) {
    int fd = open(COVER_TMP, O_WRONLY | O_CREAT | O_TRUNC, 0644);
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
    /* fsync before rename: guarantees data on disk before the Bridge sees the mtime */
    if (fsync(fd) < 0) {
        log_fmt("write", "fsync warn errno=%d (continue)", errno);
    }
    close(fd);
    if (rename(COVER_TMP, COVER_DEST) < 0) {
        log_fmt("write", "rename errno=%d", errno);
        return -1;
    }
    /* additionally a hardlink with unique path cover-<mtime>.jpg + state-file
     * for the Bridge cache-bust (picserver processes NO query strings, ENOENT
     * for cover.jpg?t=N; the hardlink gives a unique path to an existing file) */
    write_hardlink_and_state((uint32_t)time(NULL));
    return 0;
}

/* Read the target cover size (= PPS MediaItemArtworkSize of the current track) from
 * cover-target-size, written atomically by the Bridge. 0 = not available -> the caller
 * falls back to the offset pick. */
static uint32_t read_target_size(void) {
    int fd = open(COVER_TARGET_SIZE, O_RDONLY);
    if (fd < 0) return 0;
    char b[32];
    ssize_t n = read(fd, b, sizeof(b) - 1);
    close(fd);
    if (n <= 0) return 0;
    b[n] = 0;
    long v = atol(b);
    if (v <= 0 || v > 16777216L) return 0;          /* <=0 / absurd -> no target (offset fallback) */
    if (v > MAX_JPEG_SIZE) return (uint32_t)(MAX_JPEG_SIZE + 1); /* too big for the slot buffer ->
                                                        a sentinel that NEVER matches (such slots are
                                                        TRUNCATED at Setup) -> HOLD instead of wrong */
    return (uint32_t)v;
}

/* K3 -- no-cover bridge. When the current track has no exact cover yet (just switched, or its cover
 * is still decapitated/incomplete, or it has no artwork at all -> Bridge wrote the oversize sentinel),
 * write the "NOCOVER" sentinel into cover-current.path. The CoverArt shadow reads that path and pushes
 * null (stock no-cover) instead of holding the previous track's cover. Idempotent (written once per
 * transition) to avoid per-scan churn. Resets the dedupe anchor so the real cover re-writes cleanly
 * when it finally arrives. */
static int last_state_nocover = 0;   /* 1 = cover-current.path currently holds the NOCOVER sentinel */

static void write_nocover_state(void) {
    if (last_state_nocover) return;                 /* already signalled -> no churn */
    int fd = open(COVER_STATE_TMP, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) { log_fmt("nocover", "open errno=%d", errno); return; }
    if (write(fd, "NOCOVER\n", 8) != 8) { close(fd); unlink(COVER_STATE_TMP); return; }
    if (fsync(fd) < 0) { /* not fatal */ }
    close(fd);
    if (rename(COVER_STATE_TMP, COVER_STATE) < 0) { log_fmt("nocover", "rename errno=%d", errno); return; }
    last_state_nocover = 1;
    last_global_hash = 0;   /* force a clean re-write once a real cover returns */
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

    /* linear scan: walk all FILE[ markers */
    size_t i = 0;
    uint32_t token_count = 0;  /* Diagnostic */
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

    /* Diagnostic: every scan logs buflen + token_count.
     * Makes it visible in the vehicle log whether the /dev/shmem file contained the FILE[ pattern,
     * and whether the tap scan buffer was read correctly. */
    log_fmt("diag", "scan_n=%u buflen=%lu tokens=%u",
            scan_counter, (unsigned long)n, token_count);

    /* Pick (track-bound): prefers the COMPLETE slot whose Setup-declared size
     * (expected_size == PPS MediaItemArtworkSize of the currently playing track, placed by the
     * Bridge in cover-target-size) matches the target size. If none matches -> HOLD the old cover
     * (no write) instead of jumping to the last-captured (wrong) one. Without a target size
     * (boot-race / no artwork) it is exactly the offset pick. complete_scan==scan_counter keeps this
     * wrap- and re-scan-immune. */
    uint32_t target = read_target_size();
    int best = -1;
    int s;

    /* Diagnostics: make a target change visible (the Bridge wrote a new MediaItemArtworkSize = new
     * track / new artwork / no-cover sentinel). */
    if (target != last_logged_target) {
        log_fmt("target", "changed %u -> %u scan=%u", last_logged_target, target, scan_counter);
        last_logged_target = target;
        last_recovery_scan = scan_counter;   /* v3: restart the 7s-recovery timer on a new target */
    }

    if (target > 0) {
        /* K1 -- exact Setup-match ONLY. expected_size==target: the 8-byte Setup-declared size is the
         * byte-exact track identifier and survives the logger interleave (it is one short line),
         * so it cleanly separates the current track from a foreign one. actual_size==target: the
         * image is fully + cleanly reassembled (now incl. K2 decapitation recovery). Highest
         * complete_off = newest. There is NO stale/tolerance fallback: a non-exact blob is never
         * shown. The old +/-10% band admitted a foreign cover that happened to be size-close (vehicle:
         * target 153458 vs foreign 154120 = +0.43%, closer than the correct truncated 149374 = -2.7%)
         * -> that was the wrong-cover bug. No exact match -> K3 no-cover bridge below. */
        for (s = 0; s < MAX_SLOTS; s++) {
            if (slots[s].state == SLOT_COMPLETE
             && slots[s].complete_scan + COMPLETE_LOOKBACK_SCANS >= scan_counter  /* t67: lookback, not only this scan */
             && slots[s].actual_size >= MIN_JPEG_SIZE
             && slots[s].expected_size == target
             && slots[s].actual_size == target) {
                if (best < 0 || slots[s].complete_off > slots[best].complete_off) best = s;
            }
        }
        /* v3 7s-recovery: a real target has shown NOCOVER and nothing matched inside the LOOKBACK
         * window. The exact cover blob may still sit in a slot but have aged past the window (fast
         * skips / long debounce). Every RECOVERY_INTERVAL_SCANS (~8 s) retry the SAME exact double-
         * size match WITHOUT the recency bound -- no wrong-cover risk (size is the fingerprint), it
         * only relaxes the age gate. Repeats until a cover shows (last_state_nocover clears) or the
         * target changes (timer reset above). Does NOT help when the blob never reached the trace. */
        if (best < 0 && target <= MAX_JPEG_SIZE && last_state_nocover
         && (scan_counter - last_recovery_scan) >= (uint32_t)RECOVERY_INTERVAL_SCANS) {
            last_recovery_scan = scan_counter;
            for (s = 0; s < MAX_SLOTS; s++) {
                if (slots[s].state == SLOT_COMPLETE
                 && slots[s].actual_size >= MIN_JPEG_SIZE
                 && slots[s].expected_size == target
                 && slots[s].actual_size == target) {
                    if (best < 0 || slots[s].complete_off > slots[best].complete_off) best = s;
                }
            }
            if (best >= 0)
                log_fmt("pick", "recovery-match id=%d size=%u scan=%u (7s-recover, aged blob)",
                        slots[best].id, slots[best].actual_size, scan_counter);
            else
                log_fmt("recovery", "no aged blob for target=%u scan=%u (retry ~%ds)",
                        target, scan_counter, RECOVERY_INTERVAL_SCANS * POLL_INTERVAL);
        }
        if (best >= 0) {
            if (slots[best].complete_scan != scan_counter) {
                /* t67 forensics: this cover completed in an EARLIER scan (during the debounce window)
                 * and would have been lost under the old gate -- it is now recovered. */
                log_fmt("pick", "late-match id=%d size=%u completed_scan=%u now=%u (B2-recover)",
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
        if (target > MAX_JPEG_SIZE) {
            /* sentinel target (track just changed / no artwork -> Bridge wrote 16777215) -> explicit
             * no-cover bridge: push stock no-cover instead of the previous track's cover. */
            write_nocover_state();
        } else {
            /* real (or absent) target with no exact match: the cover is not yet cleanly in the trace,
             * OR it has aged out of the scan window on a long unchanged track. HOLD -- keep the current
             * cover-current.path. On a track change the Bridge already wrote NOCOVER, so HOLD never
             * re-shows a foreign cover; on a long track it keeps the correct cover instead of dropping
             * it (writing NOCOVER here was a regression vs the held-cover behaviour). */
            log_fmt("scan", "no exact match (target=%u) -> hold", target);
        }
        free(buf);
        return;
    }

    /* Hash dedupe + write */
    uint32_t h = fnv1a(slots[best].buf, slots[best].actual_size);
    if (h == last_global_hash) {
        /* flicker fix: identical cover -> NO more hardlink/mtime rotation. (formerly it bumped
         * mtime+path here to force the non-track-bound Java side to re-push; with the
         * size-match pick + Java push gate this is unnecessary and was the 3-s churn source of the
         * VC flicker.) cover-current.path stays valid on the last-written hardlink
         * (cleanup_old_hardlinks keeps HARDLINK_KEEP). */
        slot_free(best);
        free(buf);
        return;
    }
    if (write_cover_atomic(slots[best].buf, slots[best].actual_size) == 0) {
        last_global_hash = h;
        slots[best].write_hash = h;
        last_state_nocover = 0;   /* a real exact cover is now current -> clear the no-cover state */
        log_fmt("write", "id=%d size=%u setup=%u hash=%08lx",
                slots[best].id, slots[best].actual_size,
                slots[best].expected_size, (unsigned long)h);
    }
    slot_free(best);
    free(buf);
}

int main(int argc, char **argv) {
    (void)argc; (void)argv;

    JVM_LIKE_START = time(NULL);  /* for alive-uptime */

    /* self-mkdir of the target dir (idempotent). The sidecar starts via the runHMI.sh hook
     * potentially before the Bridge -- the Bridge mkdir may not have happened yet. */
    mkdir(VAR_DIR, 0755);

    signal(SIGTERM, on_term);
    signal(SIGINT,  on_term);

    /* Single-Instance-Check */
    if (acquire_pid_lock() < 0) {
        return 0;
    }

    /* boot cleanup: remove stale covers of a previous session (markerless live mode). */
    boot_cleanup();

    /* Slot-Array init */
    int s;
    for (s = 0; s < MAX_SLOTS; s++) {
        slots[s].id = -1;
        slots[s].state = SLOT_FREE;
        slots[s].write_hash = 0;
        slots[s].complete_scan = 0;
        slots[s].complete_off = 0;
    }

    log_msg("boot", "cpvc-cover-tap starting (exact Setup-match pick + K2 decapitation recovery Data+First + K3 no-cover bridge; anti-flicker via hash-dedupe)");

    /* boot delay: wait until HMI startup is over + the iAP2 trace ramps up */
    int waited = 0;
    while (running && waited < BOOT_DELAY) {
        sleep(1);
        waited++;
    }
    if (!running) return 0;

    log_msg("loop", "entering 2s scan loop");

    while (running) {
        scan_counter++;
        scan_once();
        /* keep-alive: after every block of ALIVE_LOG_EVERY scans, confirms
         * that the process is still dynamically alive (vs silent-stuck after the first flush) */
        if (scan_counter % ALIVE_LOG_EVERY == 0) {
            log_fmt("alive", "scan_n=%u uptime=%lds", scan_counter,
                    (long)(time(NULL) - JVM_LIKE_START));
        }
        int slept = 0;
        while (running && slept < POLL_INTERVAL) {
            sleep(1);
            slept++;
        }
    }

    log_msg("shutdown", "cpvc-cover-tap exiting");
    unlink(PID_FILE);
    return 0;
}
