package me.desht.dhutils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.bukkit.Color;
import org.bukkit.Effect;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * SpecialFX: map logical effect names to specifications for various effects that can be played in the world.
 * Plugins can use this to play effects for given plugin-defined events, e.g. a game was started/won/lost etc.
 * 
 * @author desht
 */
public class SpecialFX {
	public enum EffectType { EXPLOSION, LIGHTNING, EFFECT, SOUND, FIREWORK };

	private final ConfigurationSection conf;
	private final Map<String, SpecialEffect> effects;

	private float masterVolume;

	private static Map<String, Set<String>> validArgs = new HashMap<String, Set<String>>();
	private static void args(EffectType ef, String... valid) {
		validArgs.get(ef.toString()).addAll(Arrays.asList(valid));
	}
	private static boolean isValidArg(EffectType type, String arg) {
		return validArgs.get(type.toString()).contains(arg);
	}
	static {
		for (EffectType ef : EffectType.values()) {
			validArgs.put(ef.toString(), new HashSet<String>());
		}
		args(EffectType.EXPLOSION, "power", "fire" );
		args(EffectType.LIGHTNING, "power");
		args(EffectType.EFFECT, "name", "data", "radius");
		args(EffectType.SOUND, "name", "volume", "pitch");
		args(EffectType.FIREWORK, "type", "color", "fade", "flicker", "trail");
	}
	
	/**
	 * Create a SpecialFX object from the given configuration section.  This could be read from the plugin's
	 * config.yml (a common case) or just as easily constructed internally by the plugin. 
	 * 
	 * @param conf configuration object which maps logical (plugin-defined) effect names to the effect specification
	 */
	public SpecialFX(ConfigurationSection conf) {
		this.conf = conf;
		effects = new HashMap<String, SpecialFX.SpecialEffect>();
		masterVolume = (float) conf.getDouble("volume", 1.0);
	}

	/**
	 * Play the named effect at the given location.
	 * 
	 * @param loc
	 * @param effectName
	 * @throws IllegalArgumentException if the effect name is unknown or its definition is invalid
	 */
	public void playEffect(Location loc, String effectName) {
		SpecialEffect e = getEffect(effectName);
		if (e != null) {
			e.play(loc);
		}
	}

	/**
	 * Get the named effect, creating and caching a SpecialEffect object for it if necessary.
	 * 
	 * @param effectName	name of the effect
	 * @return the effect
	 * @throws IllegalArgumentException if the effect name is unknown or its definition is invalid
	 */
	public SpecialEffect getEffect(String effectName) {
		if (!effects.containsKey(effectName)) {
			try {
				effects.put(effectName, new SpecialEffect(conf.getString(effectName), masterVolume));
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("for effect name '" + effectName + "': " + e.getMessage());
			}
		}
		return effects.get(effectName);
	}

	public class SpecialEffect {
		private final EffectType type;
		private final Configuration params = new MemoryConfiguration();
		private final float volumeMult;

		public SpecialEffect(String spec) {
			this(spec, 1.0f);
		}
		
		public SpecialEffect(String spec, float volume) {
			this.volumeMult = volume;

			if (spec == null) {
				throw new IllegalArgumentException("null spec not permitted (unknown effect name?)");
			}
			String[] fields = spec.toLowerCase().split(",");
			type = EffectType.valueOf(fields[0].toUpperCase());

			for (int i = 1; i < fields.length; i++) {
				String[] val = fields[i].split("=", 2);
				if (!isValidArg(type, val[0])) {
					throw new IllegalArgumentException("invalid parameter: " + val[0]);
				}
				if (val.length == 2) {
					params.set(val[0], val[1]);
				} else {
					LogUtils.warning("missing value for parameter '" + fields[i] + "' - ignored");
				}
			}
		}

		/**
		 * Play this effect at the given location.  A null location may be passed, in which case no
		 * effect will be played, but validation of the effect specification will still be done.
		 * 
		 * @param loc
		 */
		public void play(Location loc) {
			switch (type) {
			case LIGHTNING:
				int lPower = params.getInt("power", 0);
				if (lPower > 0) {
					if (loc != null) loc.getWorld().strikeLightning(loc);
				} else {
					if (loc != null) loc.getWorld().strikeLightningEffect(loc);
				}
				break;
			case EXPLOSION:
				float ePower = (float) params.getDouble("power", 0.0);
				boolean fire = params.getBoolean("fire", false);
				if (loc != null) loc.getWorld().createExplosion(loc, ePower, fire);
				break;
			case EFFECT:
				String effectName = params.getString("name");
				if (effectName != null && !effectName.isEmpty()) {
					Effect effect = Effect.valueOf(effectName.toUpperCase());
					int data = params.getInt("data", 0);
					int radius = params.getInt("radius", 64);
					if (loc != null) loc.getWorld().playEffect(loc, effect, data, radius);
				}
				break;
			case SOUND:
				String soundName = params.getString("name");
				if (soundName != null && !soundName.isEmpty()) {
					Sound s = Sound.valueOf(soundName.toUpperCase());
					float volume = (float) params.getDouble("volume", 1.0);
					float pitch = (float) params.getDouble("pitch", 1.0);
					if (loc != null) loc.getWorld().playSound(loc, s, volume * volumeMult, pitch);
				}
				break;
			case FIREWORK:
				if (!params.contains("type")) {
					throw new IllegalArgumentException("firework effect type must have 'type' parameter");
				}

				FireworkEffect.Builder b = FireworkEffect.builder();
				b = b.with(FireworkEffect.Type.valueOf(params.getString("type").toUpperCase()));
				if (params.contains("color")) {
					b = b.withColor(getColors(params.getString("color")));
				}
				if (params.contains("fade")) {
					b = b.withColor(getColors(params.getString("fade")));
				}
				b = b.flicker(params.getBoolean("flicker", false)).trail(params.getBoolean("trail", false));
				if (loc != null) {
					try {
						FireworkEffectPlayer fwp = new FireworkEffectPlayer();
						fwp.playFirework(loc.getWorld(), loc, b.build());
					} catch (Exception e) {
						LogUtils.warning("can't play firework effect: "	 + e.getMessage());
					}
				}
				break;
			}
		}

		private Color[] getColors(String string) {
			String[] s = string.split(" ");
			Color[] colors = new Color[s.length];
			for (int i = 0; i < s.length; i++) {
				colors[i] = Color.fromRGB(Integer.parseInt(s[i], 16));
			}
			return colors;
		}

	}
}
