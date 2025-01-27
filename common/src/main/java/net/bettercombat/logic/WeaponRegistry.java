package net.bettercombat.logic;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.mojang.logging.LogUtils;
import net.bettercombat.BetterCombatMod;
import net.bettercombat.Platform;
import net.bettercombat.api.AttributesContainer;
import net.bettercombat.api.WeaponAttributes;
import net.bettercombat.api.WeaponAttributesHelper;
import net.bettercombat.network.Packets;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WeaponRegistry {
    static final Logger LOGGER = LogUtils.getLogger();
    static Map<Identifier, WeaponAttributes> registrations = new HashMap();
    static Map<Identifier, AttributesContainer> containers = new HashMap();

    public static void register(Identifier itemId, WeaponAttributes attributes) {
        registrations.put(itemId, attributes);
    }

    static WeaponAttributes getAttributes(Identifier itemId) {
        return registrations.get(itemId);
    }

    public static WeaponAttributes getAttributes(ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }
        var attributes = WeaponAttributesHelper.readFromNBT(itemStack);
        if (attributes != null) {
            return attributes;
        }
        Item item = itemStack.getItem();
        Identifier id = Registries.ITEM.getId(item);
        attributes = WeaponRegistry.getAttributes(id);
        return attributes;
    }

    // LOADING

    public static void loadAttributes(ResourceManager resourceManager) {
        loadContainers(resourceManager);

        // Resolving parents
        containers.forEach( (itemId, container) -> {
            if (!Registries.ITEM.containsId(itemId)) {
                return;
            }
            resolveAndRegisterAttributes(itemId, container);
        });
    }

    private static void loadContainers(ResourceManager resourceManager) {
        var gson = new Gson();
        Map<Identifier, AttributesContainer> containers = new HashMap();
        // Reading all attribute files
        for (var entry : resourceManager.findResources("weapon_attributes", fileName -> fileName.getPath().endsWith(".json")).entrySet()) {
            var identifier = entry.getKey();
            var resource = entry.getValue();
            try {
                // System.out.println("Checking resource: " + identifier);
                JsonReader reader = new JsonReader(new InputStreamReader(resource.getInputStream()));
                AttributesContainer container = WeaponAttributesHelper.decode(reader);
                var id = identifier
                        .toString().replace("weapon_attributes/", "");
                id = id.substring(0, id.lastIndexOf('.'));
                containers.put(Identifier.of(id), container);
            } catch (Exception e) {
                System.err.println("Failed to parse: " + identifier);
                e.printStackTrace();
            }
        }
        WeaponRegistry.containers = containers;
    }

    public static WeaponAttributes resolveAttributes(Identifier itemId, AttributesContainer container) {
        try {
            ArrayList<WeaponAttributes> resolutionChain = new ArrayList();
            AttributesContainer current = container;
            while (current != null) {
                resolutionChain.add(0, current.attributes());
                if (current.parent() != null) {
                    current = containers.get(Identifier.of(current.parent()));
                } else {
                    current = null;
                }
            }

            var empty = new WeaponAttributes(0, null, null, false, null,null);
            var resolvedAttributes = resolutionChain
                    .stream()
                    .reduce(empty, (a, b) -> {
                        if (b == null) { // I'm not sure why null can enter as `b`
                            return a;
                        }
                        return WeaponAttributesHelper.override(a, b);
                    });

            WeaponAttributesHelper.validate(resolvedAttributes);
            return resolvedAttributes;
        } catch (Exception e) {
            LOGGER.error("Failed to resolve weapon attributes for: " + itemId + ". Reason: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public static void resolveAndRegisterAttributes(Identifier itemId, AttributesContainer container) {
        var resolvedAttributes = resolveAttributes(itemId, container);
        if (resolvedAttributes != null) {
            register(itemId, resolvedAttributes);
        }
    }

    // NETWORK SYNC

    private static Packets.WeaponRegistrySync encodedRegistrations = new Packets.WeaponRegistrySync(List.of());

    public static void encodeRegistry() {

        var gson = new Gson();
        var json = gson.toJson(registrations);
        if (BetterCombatMod.config.weapon_registry_logging) {
            LOGGER.info("Weapon Attribute registry loaded: " + json);
        }

        List<String> chunks = new ArrayList<>();
        var chunkSize = 10000;
        for (int i = 0; i < json.length(); i += chunkSize) {
            chunks.add(json.substring(i, Math.min(json.length(), i + chunkSize)));
        }

        encodedRegistrations = new Packets.WeaponRegistrySync(chunks);
        var buffer = Platform.createByteBuffer();
        encodedRegistrations.write(buffer);
        LOGGER.info("Encoded Weapon Attribute registry size (with package overhead): " + buffer.readableBytes()
                + " bytes (in " + chunks.size() + " string chunks with the size of "  + chunkSize + ")");
    }

    public static void decodeRegistry(Packets.WeaponRegistrySync syncPacket) {
        String json = "";
        for (var chunk : syncPacket.chunks()) {
            json = json.concat(chunk);
        }
        LOGGER.info("Decoded Weapon Attribute registry in " + syncPacket.chunks().size() + " string chunks");
        if (BetterCombatMod.config.weapon_registry_logging) {
            LOGGER.info("Weapon Attribute registry received: " + json);
        }
        var gson = new Gson();
        Type mapType = new TypeToken<Map<String, WeaponAttributes>>() {}.getType();
        Map<String, WeaponAttributes> readRegistrations = gson.fromJson(json, mapType);
        Map<Identifier, WeaponAttributes> newRegistrations = new HashMap();
        readRegistrations.forEach((key, value) -> {
            newRegistrations.put(Identifier.of(key), value);
        });
        registrations = newRegistrations;
    }

    public static Packets.WeaponRegistrySync getEncodedRegistry() {
        return encodedRegistrations;
    }
}
