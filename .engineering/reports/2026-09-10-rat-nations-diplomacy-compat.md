# Territory Control Compat 0.5.0: Rat Nations diplomacy bridge

The Rat Nations provider retains `rat_warlords:faction`, exposes only federation/empire options, and declares the two legacy key aliases. The compat module additionally registers `territorycontrolcompat:rat_nations_diplomacy` at priority 1000. It returns a relation only if Territory Control resolves both live entities: same or allied TC factions are friendly; distinct non-allied factions are hostile; missing mappings return empty and preserve Rat Nations local diplomacy.

`clean test build` passed for 0.5.0.
