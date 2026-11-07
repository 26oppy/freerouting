package app.freerouting.settings;

/** Locking strategy for decoupling capacitor routing items. */
public enum DecouplingLockMode {
  /** Hard-locked as SYSTEM_FIXED and protected against ripup and modifications. */
  HARD,

  /** Not locked; decoupling items may be modified or ripped up by subsequent stages. */
  NONE
}
