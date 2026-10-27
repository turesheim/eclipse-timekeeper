package net.resheim.eclipse.timekeeper.domain;

/** Whether a connector has an incremental cursor or must scan its source again. */
public enum TaskSyncMode { INITIAL, INCREMENTAL, RESCAN_REQUIRED }
