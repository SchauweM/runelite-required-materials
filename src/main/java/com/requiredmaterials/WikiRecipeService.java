package com.requiredmaterials;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches materials and level requirements from a wiki page's "{{Recipe}}" templates. A page can
 * hold several - one per boat-size variant, named by "output1subtxt" - so callers pick from the
 * returned list. Cached per page for the plugin's lifetime.
 */
@Slf4j
@Singleton
public class WikiRecipeService
{
	private static final String API_URL = "https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&page=";
	private static final String USER_AGENT = "RequiredMaterials-RuneLite-Plugin/1.0";
	private static final Pattern DISAMBIG_LINK_PATTERN = Pattern.compile("\\[\\[([^\\]|]+)");

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	private final Map<String, List<Recipe>> cache = new ConcurrentHashMap<>();

	@Value
	public static class Recipe
	{
		String outputSubtext;
		Map<String, Integer> materials;
		List<String> levelRequirements;
	}

	/**
	 * Callback fires on OkHttp's thread, never the client thread - callers must hop back via
	 * ClientThread before touching any Client API.
	 */
	public void fetchRecipes(String pageName, Consumer<List<Recipe>> callback)
	{
		fetchRecipes(pageName, callback, true);
	}

	// followDisambiguation guards against chasing links indefinitely: only the first hop follows.
	private void fetchRecipes(String pageName, Consumer<List<Recipe>> callback, boolean followDisambiguation)
	{
		List<Recipe> cached = cache.get(pageName);
		if (cached != null)
		{
			callback.accept(cached);
			return;
		}

		String url = API_URL + URLEncoder.encode(pageName.replace(' ', '_'), StandardCharsets.UTF_8);
		Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Required materials: wiki lookup failed for '{}'", pageName, e);
				callback.accept(Collections.emptyList());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				String wikitext;
				try (ResponseBody body = response.body())
				{
					wikitext = !response.isSuccessful() || body == null ? null : extractWikitext(body.string());
				}
				catch (Exception e)
				{
					log.warn("Required materials: failed to parse wiki response for '{}'", pageName, e);
					wikitext = null;
				}

				if (wikitext == null)
				{
					cache.put(pageName, Collections.emptyList());
					callback.accept(Collections.emptyList());
					return;
				}

				List<Recipe> recipes = parseRecipes(wikitext);
				if (recipes.isEmpty() && followDisambiguation)
				{
					String firstLink = firstDisambiguationLink(wikitext);
					if (firstLink != null)
					{
						fetchRecipes(firstLink, callback, false);
						return;
					}
				}

				cache.put(pageName, recipes);
				callback.accept(recipes);
			}
		});
	}

	private String extractWikitext(String json)
	{
		JsonObject root = gson.fromJson(json, JsonObject.class);
		JsonObject parse = root.getAsJsonObject("parse");
		return parse == null ? null : parse.getAsJsonObject("wikitext").get("*").getAsString();
	}

	private List<Recipe> parseRecipes(String wikitext)
	{
		List<Recipe> recipes = new ArrayList<>();
		for (Map<String, String> params : extractTemplates(wikitext, "{{Recipe"))
		{
			recipes.add(buildRecipe(params));
		}
		return recipes;
	}

	/**
	 * A page with no recipe may be a disambiguation page (e.g. "Water pump" lists seven unrelated
	 * things). Its first link is the one we want.
	 */
	private String firstDisambiguationLink(String wikitext)
	{
		if (!wikitext.contains("{{disambig"))
		{
			return null;
		}
		Matcher matcher = DISAMBIG_LINK_PATTERN.matcher(wikitext);
		return matcher.find() ? matcher.group(1).trim() : null;
	}

	private Recipe buildRecipe(Map<String, String> params)
	{
		Map<String, Integer> materials = new LinkedHashMap<>();
		for (int i = 1; i <= 10; i++)
		{
			String name = params.get("mat" + i);
			if (name == null || name.isEmpty())
			{
				continue;
			}

			// A quantity of 1 is often omitted rather than written out.
			String quantity = params.get("mat" + i + "quantity");
			try
			{
				materials.put(name, quantity == null || quantity.isEmpty() ? 1 : Integer.parseInt(quantity.trim()));
			}
			catch (NumberFormatException ignored)
			{
			}
		}

		List<String> levelRequirements = new ArrayList<>();
		addLevelRequirement(levelRequirements, params, "skill1", "skill1lvl");
		addLevelRequirement(levelRequirements, params, "skill2", "skill2lvl");

		return new Recipe(params.get("output1subtxt"), materials, levelRequirements);
	}

	private void addLevelRequirement(List<String> levelRequirements, Map<String, String> params, String skillKey, String levelKey)
	{
		String skill = params.get(skillKey);
		String level = params.get(levelKey);
		if (skill != null && !skill.isEmpty() && level != null && !level.isEmpty())
		{
			levelRequirements.add("Level " + level.trim() + " " + skill.trim());
		}
	}

	/**
	 * Finds every "{{marker ... }}" block by brace-depth matching, since templates nest.
	 */
	private List<Map<String, String>> extractTemplates(String wikitext, String marker)
	{
		List<Map<String, String>> templates = new ArrayList<>();
		int searchFrom = 0;
		while (true)
		{
			int start = wikitext.indexOf(marker, searchFrom);
			if (start == -1)
			{
				break;
			}

			int depth = 0;
			int i = start;
			int end = -1;
			while (i < wikitext.length() - 1)
			{
				if (wikitext.startsWith("{{", i))
				{
					depth++;
					i += 2;
				}
				else if (wikitext.startsWith("}}", i))
				{
					depth--;
					i += 2;
					if (depth == 0)
					{
						end = i;
						break;
					}
				}
				else
				{
					i++;
				}
			}

			if (end == -1)
			{
				break;
			}

			templates.add(parseTemplateParams(wikitext.substring(start + 2, end - 2)));
			searchFrom = end;
		}
		return templates;
	}

	private Map<String, String> parseTemplateParams(String block)
	{
		List<String> parts = splitRespectingNesting(block, '|');
		Map<String, String> params = new LinkedHashMap<>();
		for (int i = 1; i < parts.size(); i++)
		{
			int eq = parts.get(i).indexOf('=');
			if (eq == -1)
			{
				continue;
			}
			params.put(parts.get(i).substring(0, eq).trim(), parts.get(i).substring(eq + 1).trim());
		}
		return params;
	}

	private List<String> splitRespectingNesting(String s, char delimiter)
	{
		List<String> parts = new ArrayList<>();
		int depth = 0;
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			if (c == '{' || c == '[')
			{
				depth++;
			}
			else if (c == '}' || c == ']')
			{
				depth--;
			}

			if (c == delimiter && depth == 0)
			{
				parts.add(current.toString());
				current.setLength(0);
			}
			else
			{
				current.append(c);
			}
		}
		parts.add(current.toString());
		return parts;
	}
}
