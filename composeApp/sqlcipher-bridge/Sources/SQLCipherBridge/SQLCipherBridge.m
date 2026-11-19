#import "SQLCipherBridge.h"

#import <Foundation/Foundation.h>
#include <dispatch/dispatch.h>
#include <dlfcn.h>
#include <stdint.h>

/*
 * SQLCipher.framework exports the same sqlite3_* symbols as Apple's libsqlite3. When anything
 * in the dependency graph links libsqlite3 (Firebase does, from FirebaseAnalyticsWrapper),
 * the linker binds our calls to the system library and the database silently stays plaintext.
 *
 * Every sqlite3 function is therefore resolved at runtime from SQLCipher.framework itself.
 * Link order no longer matters.
 */

typedef void (*sc_destructor)(void *);

typedef int (*sc_fn_open_v2)(const char *, void **, int, const char *);
typedef int (*sc_fn_close_v2)(void *);
typedef int (*sc_fn_key)(void *, const void *, int);
typedef int (*sc_fn_extended_result_codes)(void *, int);
typedef int (*sc_fn_prepare16_v2)(void *, const void *, int, void **, const void **);
typedef int (*sc_fn_step)(void *);
typedef int (*sc_fn_reset)(void *);
typedef int (*sc_fn_clear_bindings)(void *);
typedef int (*sc_fn_finalize)(void *);
typedef int (*sc_fn_stmt_busy)(void *);
typedef int (*sc_fn_bind_blob)(void *, int, const void *, int, sc_destructor);
typedef int (*sc_fn_bind_text16)(void *, int, const void *, int, sc_destructor);
typedef int (*sc_fn_bind_double)(void *, int, double);
typedef int (*sc_fn_bind_int64)(void *, int, int64_t);
typedef int (*sc_fn_bind_null)(void *, int);
typedef const void *(*sc_fn_column_blob)(void *, int);
typedef int (*sc_fn_column_bytes)(void *, int);
typedef int (*sc_fn_column_count)(void *);
typedef double (*sc_fn_column_double)(void *, int);
typedef int64_t (*sc_fn_column_int64)(void *, int);
typedef const void *(*sc_fn_column_name16)(void *, int);
typedef const void *(*sc_fn_column_text16)(void *, int);
typedef int (*sc_fn_column_type)(void *, int);
typedef const void *(*sc_fn_errmsg16)(void *);
typedef int (*sc_fn_errcode)(void *);
typedef const char *(*sc_fn_libversion)(void);

static struct {
    sc_fn_open_v2 open_v2;
    sc_fn_close_v2 close_v2;
    sc_fn_key key;
    sc_fn_extended_result_codes extended_result_codes;
    sc_fn_prepare16_v2 prepare16_v2;
    sc_fn_step step;
    sc_fn_reset reset;
    sc_fn_clear_bindings clear_bindings;
    sc_fn_finalize finalize;
    sc_fn_stmt_busy stmt_busy;
    sc_fn_bind_blob bind_blob;
    sc_fn_bind_text16 bind_text16;
    sc_fn_bind_double bind_double;
    sc_fn_bind_int64 bind_int64;
    sc_fn_bind_null bind_null;
    sc_fn_column_blob column_blob;
    sc_fn_column_bytes column_bytes;
    sc_fn_column_count column_count;
    sc_fn_column_double column_double;
    sc_fn_column_int64 column_int64;
    sc_fn_column_name16 column_name16;
    sc_fn_column_text16 column_text16;
    sc_fn_column_type column_type;
    sc_fn_errmsg16 errmsg16;
    sc_fn_errcode errcode;
    sc_fn_libversion libversion;
} sc;

static int sc_ready = 0;

#define SC_SQLITE_MISUSE 21
#define SC_SQLITE_NOMEM 7

/* SQLITE_TRANSIENT: sqlite copies the value, the Kotlin buffer may die right after the call. */
#define SC_TRANSIENT ((sc_destructor) - 1)

static void *sc_open_sqlcipher(void) {
    void *handle = dlopen("@rpath/SQLCipher.framework/SQLCipher", RTLD_NOW);
    if (handle != NULL) {
        return handle;
    }
    NSString *path = [[[NSBundle mainBundle] privateFrameworksPath]
        stringByAppendingPathComponent:@"SQLCipher.framework/SQLCipher"];
    if (path == nil) {
        return NULL;
    }
    return dlopen([path fileSystemRepresentation], RTLD_NOW);
}

static int sc_load(void) {
    void *handle = sc_open_sqlcipher();
    if (handle == NULL) {
        NSLog(@"SQLCipherBridge: failed to dlopen SQLCipher.framework: %s", dlerror());
        return 0;
    }

    int loaded = 1;
#define SC_LOAD(field, name)                                                       \
    do {                                                                           \
        sc.field = (sc_fn_##field)dlsym(handle, "sqlite3_" name);                  \
        if (sc.field == NULL) {                                                    \
            NSLog(@"SQLCipherBridge: missing symbol sqlite3_%s", name);            \
            loaded = 0;                                                            \
        }                                                                          \
    } while (0)

    SC_LOAD(open_v2, "open_v2");
    SC_LOAD(close_v2, "close_v2");
    SC_LOAD(key, "key");
    SC_LOAD(extended_result_codes, "extended_result_codes");
    SC_LOAD(prepare16_v2, "prepare16_v2");
    SC_LOAD(step, "step");
    SC_LOAD(reset, "reset");
    SC_LOAD(clear_bindings, "clear_bindings");
    SC_LOAD(finalize, "finalize");
    SC_LOAD(stmt_busy, "stmt_busy");
    SC_LOAD(bind_blob, "bind_blob");
    SC_LOAD(bind_text16, "bind_text16");
    SC_LOAD(bind_double, "bind_double");
    SC_LOAD(bind_int64, "bind_int64");
    SC_LOAD(bind_null, "bind_null");
    SC_LOAD(column_blob, "column_blob");
    SC_LOAD(column_bytes, "column_bytes");
    SC_LOAD(column_count, "column_count");
    SC_LOAD(column_double, "column_double");
    SC_LOAD(column_int64, "column_int64");
    SC_LOAD(column_name16, "column_name16");
    SC_LOAD(column_text16, "column_text16");
    SC_LOAD(column_type, "column_type");
    SC_LOAD(errmsg16, "errmsg16");
    SC_LOAD(errcode, "errcode");
    SC_LOAD(libversion, "libversion");

#undef SC_LOAD

    if (loaded) {
        NSLog(@"SQLCipherBridge: loaded SQLCipher.framework, sqlite3_libversion = %s",
              sc.libversion());
    }
    return loaded;
}

int sc_initialize(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{ sc_ready = sc_load(); });
    return sc_ready;
}

static int sc_is_ready(void) {
    return sc_ready != 0 ? 1 : sc_initialize();
}

#define SC_GUARD_INT()          \
    do {                        \
        if (!sc_is_ready()) {   \
            return SC_SQLITE_MISUSE; \
        }                       \
    } while (0)

const char *sc_libversion(void) {
    if (!sc_is_ready()) {
        return NULL;
    }
    return sc.libversion != NULL ? sc.libversion() : NULL;
}

int sc_open_v2(const char *filename, sc_sqlite3 **ppDb, int flags, const char *zVfs) {
    SC_GUARD_INT();
    if (ppDb == NULL) {
        return SC_SQLITE_MISUSE;
    }
    *ppDb = NULL;
    void *db = NULL;
    int rc = sc.open_v2(filename, &db, flags, zVfs);
    if (rc != 0) {
        if (db != NULL) {
            sc.close_v2(db);
        }
        return rc;
    }
    *ppDb = (sc_sqlite3 *)db;
    return 0;
}

int sc_close_v2(sc_sqlite3 *db) {
    SC_GUARD_INT();
    if (db == NULL) {
        return SC_SQLITE_MISUSE;
    }
    return sc.close_v2((void *)db);
}

int sc_key(sc_sqlite3 *db, const int8_t *pKey, int nKey) {
    SC_GUARD_INT();
    if (db == NULL) {
        return SC_SQLITE_MISUSE;
    }
    return sc.key((void *)db, pKey, nKey);
}

int sc_extended_result_codes(sc_sqlite3 *db, int onoff) {
    SC_GUARD_INT();
    if (db == NULL) {
        return SC_SQLITE_MISUSE;
    }
    return sc.extended_result_codes((void *)db, onoff);
}

int sc_prepare16_v2(sc_sqlite3 *db, const uint16_t *zSql, sc_sqlite3_stmt **ppStmt) {
    SC_GUARD_INT();
    if (db == NULL || ppStmt == NULL) {
        return SC_SQLITE_MISUSE;
    }
    *ppStmt = NULL;
    void *stmt = NULL;
    /* Negative length: read up to the NUL terminator. The last parameter (pzTail) must be
       passed because sqlite writes through it when it is not NULL. */
    int rc = sc.prepare16_v2((void *)db, zSql, -1, &stmt, NULL);
    if (rc != 0) {
        return rc;
    }
    *ppStmt = (sc_sqlite3_stmt *)stmt;
    return 0;
}

int sc_step(sc_sqlite3_stmt *stmt) {
    SC_GUARD_INT();
    return sc.step((void *)stmt);
}

int sc_reset(sc_sqlite3_stmt *stmt) {
    SC_GUARD_INT();
    return sc.reset((void *)stmt);
}

int sc_clear_bindings(sc_sqlite3_stmt *stmt) {
    SC_GUARD_INT();
    return sc.clear_bindings((void *)stmt);
}

int sc_finalize(sc_sqlite3_stmt *stmt) {
    SC_GUARD_INT();
    if (stmt == NULL) {
        return SC_SQLITE_MISUSE;
    }
    return sc.finalize((void *)stmt);
}

int sc_stmt_busy(sc_sqlite3_stmt *stmt) {
    SC_GUARD_INT();
    return sc.stmt_busy((void *)stmt);
}

int sc_bind_blob(sc_sqlite3_stmt *stmt, int index, const void *value, int size) {
    SC_GUARD_INT();
    return sc.bind_blob((void *)stmt, index, value, size, SC_TRANSIENT);
}

int sc_bind_text16(sc_sqlite3_stmt *stmt, int index, const uint16_t *value) {
    SC_GUARD_INT();
    /* Negative length: sqlite copies up to the NUL terminator. */
    return sc.bind_text16((void *)stmt, index, value, -1, SC_TRANSIENT);
}

int sc_bind_double(sc_sqlite3_stmt *stmt, int index, double value) {
    SC_GUARD_INT();
    return sc.bind_double((void *)stmt, index, value);
}

int sc_bind_int64(sc_sqlite3_stmt *stmt, int index, int64_t value) {
    SC_GUARD_INT();
    return sc.bind_int64((void *)stmt, index, value);
}

int sc_bind_null(sc_sqlite3_stmt *stmt, int index) {
    SC_GUARD_INT();
    return sc.bind_null((void *)stmt, index);
}

const uint8_t *sc_column_blob(sc_sqlite3_stmt *stmt, int index) {
    if (!sc_is_ready()) {
        return NULL;
    }
    return (const uint8_t *)sc.column_blob((void *)stmt, index);
}

int sc_column_bytes(sc_sqlite3_stmt *stmt, int index) {
    SC_GUARD_INT();
    return sc.column_bytes((void *)stmt, index);
}

int sc_column_count(sc_sqlite3_stmt *stmt) {
    SC_GUARD_INT();
    return sc.column_count((void *)stmt);
}

double sc_column_double(sc_sqlite3_stmt *stmt, int index) {
    if (!sc_is_ready()) {
        return 0.0;
    }
    return sc.column_double((void *)stmt, index);
}

int64_t sc_column_int64(sc_sqlite3_stmt *stmt, int index) {
    if (!sc_is_ready()) {
        return 0;
    }
    return sc.column_int64((void *)stmt, index);
}

const uint16_t *sc_column_name16(sc_sqlite3_stmt *stmt, int index) {
    if (!sc_is_ready()) {
        return NULL;
    }
    return (const uint16_t *)sc.column_name16((void *)stmt, index);
}

const uint16_t *sc_column_text16(sc_sqlite3_stmt *stmt, int index) {
    if (!sc_is_ready()) {
        return NULL;
    }
    return (const uint16_t *)sc.column_text16((void *)stmt, index);
}

int sc_column_type(sc_sqlite3_stmt *stmt, int index) {
    SC_GUARD_INT();
    return sc.column_type((void *)stmt, index);
}

const uint16_t *sc_errmsg16(sc_sqlite3 *db) {
    if (!sc_is_ready()) {
        return NULL;
    }
    return (const uint16_t *)sc.errmsg16((void *)db);
}

int sc_errcode(sc_sqlite3 *db) {
    SC_GUARD_INT();
    return sc.errcode((void *)db);
}

int sc_SQLITE_OK(void) { return 0; }
int sc_SQLITE_ROW(void) { return 100; }
int sc_SQLITE_DONE(void) { return 101; }
int sc_SQLITE_MISUSE(void) { return SC_SQLITE_MISUSE; }
int sc_SQLITE_RANGE(void) { return 25; }
int sc_SQLITE_NOMEM(void) { return SC_SQLITE_NOMEM; }
int sc_SQLITE_NULL(void) { return 5; }
int sc_SQLITE_OPEN_READWRITE(void) { return 0x00000002; }
int sc_SQLITE_OPEN_CREATE(void) { return 0x00000004; }
