// ╔══════════════════════════════════════════════════════════════╗
// ║  DEPRECATED + DEAD — removed 2026-08-31                      ║
// ║  This file contained MemoryAtomEntity + MemoryAtomDao         ║
// ║  which was NEVER instantiated/wired in the runtime.        ║
// ║                                                               ║
// ║  The ACTUAL canonical 7-layer EMERY memory system is:        ║
// ║  → PurpClawDatabase :: seven_layer_memory table             ║
// ║  → MemoryItemEntity (layer/score/key/content/timestamp)     ║
// ║  → MemoryDao in PurpClawDatabase                           ║
// ║  → MemoryGateway (core/runtime/MemoryGateway.kt)            ║
// ║  → SevenLayerMemoryScreen (UI layer)                        ║
// ║                                                               ║
// ║  NO other memory system exists. This file was dead code.     ║
// ║  Council complaint "SQLite with JSON blobs" was WRONG:      ║
// ║  the seven_layer_memory table IS the proper 7-layer store.  ║
// ╚══════════════════════════════════════════════════════════════╝

package com.example.core.database.secondary

// This file intentionally left blank (was: MemoryAtomEntity + MemoryAtomDao)
// See deprecated note above. → use MemoryItemEntity in PurpClawDatabase instead.
