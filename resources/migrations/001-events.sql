-- Append-only event log. This table IS the game state; everything in memory is
-- a fold over it. Nothing here is ever UPDATEd or DELETEd during play.
CREATE TABLE IF NOT EXISTS events (
  id       INTEGER PRIMARY KEY AUTOINCREMENT,
  room_id  TEXT    NOT NULL,
  seq      INTEGER NOT NULL,      -- per-room ordinal, 0-based
  type     TEXT    NOT NULL,      -- denormalised for cheap inspection/debugging
  payload  TEXT    NOT NULL,      -- the full event as EDN
  at       INTEGER NOT NULL,      -- epoch ms, for recaps; NOT used by the engine
  UNIQUE (room_id, seq)           -- makes double-append a hard error, not a silent dup
);

CREATE INDEX IF NOT EXISTS events_room_seq ON events (room_id, seq);

-- Room metadata. Deliberately thin: anything the engine needs lives in the log.
-- This exists so we can list/expire rooms without replaying every one of them.
CREATE TABLE IF NOT EXISTS rooms (
  room_id    TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  phase      TEXT,                -- last known phase, for the room list only
  finished   INTEGER NOT NULL DEFAULT 0
);
