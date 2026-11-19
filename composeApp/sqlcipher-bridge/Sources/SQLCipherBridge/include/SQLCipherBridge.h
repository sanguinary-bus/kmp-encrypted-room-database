#ifndef SQLCIPHER_BRIDGE_H
#define SQLCIPHER_BRIDGE_H

#include <stdint.h>

/*
 * Opaque handles. They are never dereferenced by Kotlin: every pointer is passed
 * straight back to the bridge, which casts it to the matching sqlite3 type.
 * The structs are declared complete (rather than forward declared) so cinterop
 * can generate a concrete type for the pointers.
 */
typedef struct sc_sqlite3 {
    char opaque[1];
} sc_sqlite3;

typedef struct sc_sqlite3_stmt {
    char opaque[1];
} sc_sqlite3_stmt;

/* Loads SQLCipher.framework through dlopen/dlsym. Returns 1 on success, 0 on failure. */
int sc_initialize(void);

/* Returns the SQLCipher library version, or NULL when the bridge failed to load. */
const char *sc_libversion(void);

int sc_open_v2(const char *filename, sc_sqlite3 **ppDb, int flags, const char *zVfs);
int sc_close_v2(sc_sqlite3 *db);
int sc_key(sc_sqlite3 *db, const int8_t *pKey, int nKey);
int sc_extended_result_codes(sc_sqlite3 *db, int onoff);

/* zSql must be NUL terminated; the length is derived from the terminator. */
int sc_prepare16_v2(sc_sqlite3 *db, const uint16_t *zSql, sc_sqlite3_stmt **ppStmt);
int sc_step(sc_sqlite3_stmt *stmt);
int sc_reset(sc_sqlite3_stmt *stmt);
int sc_clear_bindings(sc_sqlite3_stmt *stmt);
int sc_finalize(sc_sqlite3_stmt *stmt);
int sc_stmt_busy(sc_sqlite3_stmt *stmt);

/* BLOB and text values are always bound with SQLITE_TRANSIENT. */
int sc_bind_blob(sc_sqlite3_stmt *stmt, int index, const void *value, int size);
int sc_bind_text16(sc_sqlite3_stmt *stmt, int index, const uint16_t *value);
int sc_bind_double(sc_sqlite3_stmt *stmt, int index, double value);
int sc_bind_int64(sc_sqlite3_stmt *stmt, int index, int64_t value);
int sc_bind_null(sc_sqlite3_stmt *stmt, int index);

const uint8_t *sc_column_blob(sc_sqlite3_stmt *stmt, int index);
int sc_column_bytes(sc_sqlite3_stmt *stmt, int index);
int sc_column_count(sc_sqlite3_stmt *stmt);
double sc_column_double(sc_sqlite3_stmt *stmt, int index);
int64_t sc_column_int64(sc_sqlite3_stmt *stmt, int index);
const uint16_t *sc_column_name16(sc_sqlite3_stmt *stmt, int index);
const uint16_t *sc_column_text16(sc_sqlite3_stmt *stmt, int index);
int sc_column_type(sc_sqlite3_stmt *stmt, int index);

const uint16_t *sc_errmsg16(sc_sqlite3 *db);
int sc_errcode(sc_sqlite3 *db);

/* Constants are exposed as functions because cinterop does not import macros. */
int sc_SQLITE_OK(void);
int sc_SQLITE_ROW(void);
int sc_SQLITE_DONE(void);
int sc_SQLITE_MISUSE(void);
int sc_SQLITE_RANGE(void);
int sc_SQLITE_NOMEM(void);
int sc_SQLITE_NULL(void);
int sc_SQLITE_OPEN_READWRITE(void);
int sc_SQLITE_OPEN_CREATE(void);

#endif /* SQLCIPHER_BRIDGE_H */
