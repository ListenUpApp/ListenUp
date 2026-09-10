// The web worker side of androidx.sqlite:sqlite-web's WebWorkerSQLiteDriver
// (2.7.0). The artifact ships only the driver half — the protocol is documented
// on WebWorkerSQLiteDriver's KDoc (open/prepare/step/close), and consumers
// supply the worker. This implementation started as the Room team's reference at
// github.com/danysantiago/room-web-demo (Apache-2.0),
// sqliteWasmWorker/worker/worker.js, backed by @sqlite.org/sqlite-wasm with
// OPFS persistence (sqlite3.oo1.OpfsDb — requires COOP/COEP headers).
//
// NO LONGER VERBATIM. Local divergences, to be preserved across any upstream
// reconciliation:
//   1. `close` null-checks its ids instead of testing them for truthiness — the
//      counters start at 0, so the first database and the first statement were
//      never closed.
//   2. No per-message logging — it printed every prepared statement's SQL and
//      every step's bindings to the production console.
//   3. A rejected `sqlite3InitModule()` fails the queued and every subsequent
//      request instead of leaving them unanswered forever.
//
// NOT a divergence, and must never become one: `close` answers only on failure.
// It is the one command the driver sends fire-and-forget — CoroutineWebWorker.
// sendRequest(request) posts the message and registers NO pending entry — so a
// success reply is an id onMessage never expected, and it throws
// `IllegalStateException: ... was not expected.` straight out of the Worker's
// onmessage, once per finalized statement and closed database. Nothing in Kotlin
// can catch that. An ERROR reply is different and stays: onMessage routes an
// unknown id that carries `error` to onError(), which fails every in-flight
// request instead of throwing. See SqliteWorkerProtocolTest.
// Sunset: replace with official packaging if/when androidx ships the worker.
import sqlite3InitModule from '@sqlite.org/sqlite-wasm';

let sqlite3 = null;

// Maps to track of active database connections and prepared statements by their unique IDs.
const databases = new Map(); // stores databaseId -> SQLiteDbObject
const statements = new Map(); // stores statementId -> SQLiteStatementObject

// Counters to generate unique IDs for new database connections and statements.
let nextDatabaseId = 0;
let nextStatementId = 0;

function openRequest(id, requestData) {
    try {
        const newDatabaseId = nextDatabaseId++;
        const newDatabase = new sqlite3.oo1.OpfsDb(requestData.fileName);
        databases.set(newDatabaseId, newDatabase);
        postMessage({'id': id, data: {'databaseId': newDatabaseId}});
    } catch (error) {
        postMessage({'id': id, error: error.message});
    }
}

function prepareRequest(id, requestData) {
    try {
        const newStatementId = nextStatementId++;
        const resultData = {
            'statementId': newStatementId,
            'parameterCount': 0,
            'columnNames': []
        };
        const database = databases.get(requestData.databaseId);
        if (!database) {
            postMessage({'id': id, error: "Invalid database ID: " + requestData.databaseId});
            return;
        }
        const statement = database.prepare(requestData.sql);
        statements.set(newStatementId, statement);
        resultData.parameterCount = sqlite3.capi.sqlite3_bind_parameter_count(statement);
        for (let i = 0; i < statement.columnCount; i++) {
            resultData.columnNames.push(sqlite3.capi.sqlite3_column_name(statement, i));
        }
        postMessage({'id': id, data: resultData});
    } catch (error) {
        postMessage({'id': id, error: error.message});
    }
}

function stepRequest(id, requestData) {
    const statement = statements.get(requestData.statementId);
    if (!statement) {
        postMessage({'id': id, error: "Invalid statement ID: " + requestData.statementId});
        return;
    }
    try {
        const resultData = {
            'rows': [],
            'columnTypes': []
        };
        statement.reset()
        statement.clearBindings()
        for (let i = 0; i < requestData.bindings.length; i++) {
            statement.bind(i + 1, requestData.bindings[i]);
        }
        while (statement.step()) {
            if (!resultData.columnTypes.length) {
                for (let i = 0; i < statement.columnCount; i++) {
                    resultData.columnTypes.push(sqlite3.capi.sqlite3_column_type(statement, i));
                }
            }
            resultData.rows.push(statement.get([]));
        }
        postMessage({'id': id, data: resultData});
    } catch (error) {
        postMessage({'id': id, error: error.message});
    }
}

function closeRequest(id, requestData) {
    // `!= null` rather than truthiness: the id counters start at 0, so `if (requestData.databaseId)`
    // was false for the first database and the first statement ever opened — in practice the only
    // ones the app has — and their OPFS handles were held for the life of the worker.
    if (requestData.statementId != null) {
        const statement = statements.get(requestData.statementId);
        if (!statement) {
            postMessage({'id': id, error: "Invalid statement ID: " + requestData.statementId});
            return;
        }
        try {
            statement.finalize();
            statements.delete(requestData.statementId);
        } catch (error) {
            postMessage({'id': id, error: error.message});
        }
    }

    if (requestData.databaseId != null) {
        const database = databases.get(requestData.databaseId);
        if (!database) {
            postMessage({'id': id, error: "Invalid database ID: " + requestData.databaseId});
            return;
        }
        try {
            database.close();
            databases.delete(requestData.databaseId);
        } catch (error) {
            postMessage({'id': id, error: error.message});
        }
    }

    // Deliberately silent on success — see the header note. The driver holds no pending entry for
    // a `close`, so the only correct answer to one that worked is no answer at all.
}

// A map that links command names (strings) to their respective handler functions.
const commandMap = {
    'open': openRequest,
    'prepare': prepareRequest,
    'step': stepRequest,
    'close': closeRequest,
};

function handleMessage(e) {
    const requestMsg = e.data;
    if (!Object.hasOwn(requestMsg, 'data') && requestMsg.data == null) {
        postMessage(
            {'id': requestMsg.id, 'error': "Invalid request, missing 'data'."}
        );
        return;
    }
    if (!Object.hasOwn(requestMsg.data, 'cmd') && requestMsg.data.cmd == null) {
        postMessage(
            {'id': requestMsg.id, 'error': "Invalid request, missing 'cmd'."}
        );
        return;
    }
    const command = requestMsg.data.cmd;
    const requestHandler = commandMap[command];
    if (requestHandler) {
        requestHandler(requestMsg.id, requestMsg.data);
    } else {
        postMessage(
            {'id': requestMsg.id, 'error': "Invalid request, unknown command: '" + command + "'."}
        );
    }
}

const messageQueue = [];
// A rejected init is permanent: every already-queued and every future request must be
// answered with the reason, or the driver's CompletableDeferred never completes and the
// tab spins forever with nothing in the console to act on.
let initError = null;

function failRequest(requestMsg, reason) {
    postMessage({'id': requestMsg.id, 'error': "SQLite worker failed to start: " + reason});
}

onmessage = (e) => {
    if (initError !== null) {
        failRequest(e.data, initError);
    } else if (!sqlite3) {
        messageQueue.push(e);
    } else {
        handleMessage(e);
    }
};

sqlite3InitModule()
    .then(instance => {
        sqlite3 = instance;
        while (messageQueue.length > 0) {
            handleMessage(messageQueue.shift());
        }
    })
    .catch(error => {
        initError = String(error && error.message ? error.message : error);
        while (messageQueue.length > 0) {
            failRequest(messageQueue.shift().data, initError);
        }
    });
