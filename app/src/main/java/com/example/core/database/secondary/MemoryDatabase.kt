// ╔══════════════════════════════════════════════════════════════╗
// ║  DEPRECATED + DEAD — removed 2026-08-31                      ║
// ║  MemoryDatabase ("memory.db") was NEVER instantiated.       ║
// ║  MemoryAtomDao was NEVER implemented or wired.              ║
// ║                                                               ║
// ║  The ACTUAL canonical 7-layer EMERY memory system is:        ║
// ║  → PurpClawDatabase :: seven_layer_memory table             ║
// ║  → MemoryItemEntity (layer/score/key/content/timestamp)     ║
// ║  → MemoryDao in PurpClawDatabase                           ║
// ║  → MemoryGateway (core/runtime/MemoryGateway.kt)            ║
// ║  → SevenLayerMemoryScreen (UI layer)                        ║
// ║                                                               ║
// ║  NO second memory system. "memory.db" was dead architecture. ║
// ╚══════════════════════════════════════════════════════════════╝

package com.example.core.database.secondary

// This file intentionally left blank (was: MemoryDatabase + MemoryAtomDao)
// See deprecated note above. → use PurpClawDatabase instead.
