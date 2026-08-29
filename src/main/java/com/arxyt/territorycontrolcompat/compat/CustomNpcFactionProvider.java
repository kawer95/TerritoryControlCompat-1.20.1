package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.EntityFactionProvider;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Optional CNPC bridge. All CNPC references are resolved by name so the compat jar remains
 * loadable when CustomNPCs is not installed (or when a fork changes its public API).
 */
public final class CustomNpcFactionProvider implements EntityFactionProvider {
    public static final String PROVIDER_ID = "customnpcs:faction";
    public static final String MOD_ID = "customnpcs";
    private static final String NPC_CLASS_NAME = "noppes.npcs.entity.EntityNPCInterface";
    private static final String NPC_PACKAGE_PREFIX = "noppes.npcs.entity.";
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Class<?> npcClass;
    private final Method getFactionMethod;
    private final Class<?> npcApiClass;
    private final Method factionIdMethod;
    private final Method factionNameMethod;
    private final Method factionColorMethod;
    private final Method apiInstanceMethod;
    private final Method apiGetFactionsMethod;
    private final Method factionListMethod;
    private volatile boolean enabled;
    private boolean failureLogged;

    public CustomNpcFactionProvider() {
        Class<?> resolvedNpcClass = null;
        Method resolvedGetFaction = null;
        Class<?> resolvedApiClass = null;
        Method resolvedFactionId = null;
        Method resolvedFactionName = null;
        Method resolvedFactionColor = null;
        Method resolvedApiInstance = null;
        Method resolvedApiGetFactions = null;
        Method resolvedFactionList = null;
        boolean resolved = false;
        try {
            resolvedNpcClass = Class.forName(NPC_CLASS_NAME);
            resolvedGetFaction = resolvedNpcClass.getMethod("getFaction");
            resolvedFactionId = resolvedGetFaction.getReturnType().getMethod("getId");
            resolvedApiClass = Class.forName("noppes.npcs.api.NpcAPI");
            resolvedApiInstance = resolvedApiClass.getMethod("Instance");
            resolvedApiGetFactions = resolvedApiClass.getMethod("getFactions");
            resolvedFactionList = resolvedApiGetFactions.getReturnType().getMethod("list");
            Class<?> factionApiClass = Class.forName("noppes.npcs.api.handler.data.IFaction");
            resolvedFactionName = factionApiClass.getMethod("getName");
            resolvedFactionColor = factionApiClass.getMethod("getColor");
            resolved = true;
        } catch (Throwable throwable) {
            logFailure("CNPC reflection initialization failed; faction mappings are disabled", throwable);
        }
        npcClass = resolvedNpcClass;
        getFactionMethod = resolvedGetFaction;
        npcApiClass = resolvedApiClass;
        factionIdMethod = resolvedFactionId;
        factionNameMethod = resolvedFactionName;
        factionColorMethod = resolvedFactionColor;
        apiInstanceMethod = resolvedApiInstance;
        apiGetFactionsMethod = resolvedApiGetFactions;
        factionListMethod = resolvedFactionList;
        enabled = resolved;
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public boolean supports(Entity entity) {
        if (entity == null) {
            return false;
        }
        return npcClass != null ? npcClass.isInstance(entity)
                : entity.getClass().getName().startsWith(NPC_PACKAGE_PREFIX);
    }

    @Override
    public Resolution resolve(Entity entity) {
        if (!supports(entity)) {
            return Resolution.notApplicable();
        }
        if (!enabled || getFactionMethod == null) {
            return Resolution.unmapped();
        }
        try {
            Object faction = getFactionMethod.invoke(entity);
            String key = factionKey(faction);
            return key == null ? Resolution.unmapped() : Resolution.mapped(key);
        } catch (Throwable throwable) {
            disable("CNPC faction lookup failed; faction mappings are now disabled", throwable);
            return Resolution.unmapped();
        }
    }

    @Override
    public List<Option> options(ServerLevel level) {
        if (!enabled || npcApiClass == null) {
            throw new IllegalStateException("CNPC reflection provider is unavailable");
        }
        try {
            Object api = apiInstanceMethod.invoke(null);
            Object handler = apiGetFactionsMethod.invoke(api);
            Object listed = factionListMethod.invoke(handler);
            List<Option> result = new ArrayList<>();
            for (Object faction : values(listed)) {
                String key = factionKey(faction);
                if (key == null) {
                    continue;
                }
                String name = stringValue(factionNameMethod.invoke(faction));
                int color = colorValue(factionColorMethod.invoke(faction));
                result.add(new Option(key, name, color));
            }
            result.sort(Comparator.comparingInt(option -> parseInt(option.key())));
            return List.copyOf(result);
        } catch (Throwable throwable) {
            disable("CNPC faction directory lookup failed; faction mappings are now disabled", throwable);
            throw new IllegalStateException("CNPC faction directory unavailable", throwable);
        }
    }

    private String factionKey(Object faction) throws ReflectiveOperationException {
        if (faction == null) {
            return null;
        }
        Object id = factionIdMethod.invoke(faction);
        if (id instanceof Number number) {
            return String.valueOf(number.intValue());
        }
        if (id == null) {
            return null;
        }
        try {
            return String.valueOf(Integer.parseInt(id.toString().trim()));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static List<Object> values(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(result::add);
            return result;
        }
        if (value.getClass().isArray()) {
            List<Object> result = new ArrayList<>(Array.getLength(value));
            for (int i = 0; i < Array.getLength(value); i++) {
                result.add(Array.get(value, i));
            }
            return result;
        }
        return List.of(value);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int colorValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue() & 0xFFFFFF;
        }
        if (value != null) {
            String text = value.toString().trim();
            try {
                return Integer.decode(text) & 0xFFFFFF;
            } catch (NumberFormatException ignored) {
                try {
                    return Integer.parseInt(text.replace("#", ""), 16) & 0xFFFFFF;
                } catch (NumberFormatException ignoredAgain) {
                    // Display metadata is non-authoritative; use a neutral color.
                }
            }
        }
        return 0xFFFFFF;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private synchronized void disable(String message, Throwable throwable) {
        enabled = false;
        logFailure(message, throwable);
    }

    private synchronized void logFailure(String message, Throwable throwable) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        LOGGER.warn("[Territory Control / 界域沙盘] {}", message, throwable);
    }
}
