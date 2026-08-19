package com.requiredmaterials;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	private static final String API_URL = "https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&redirects=true&page=";
	private static final String USER_AGENT = "RequiredMaterials-RuneLite-Plugin/1.0";
	// Disambiguation pages link either as plain wikilinks or through the plink/ilink templates.
	// Furniture pages carry the ids of the objects the build places in the house, versioned as
	// "id1"/"id2" when a page covers several variants.
	private static final Pattern OBJECT_ID_PATTERN = Pattern.compile("^\\|\\s*id\\d*\\s*=\\s*([0-9,\\s]+)$", Pattern.MULTILINE);
	private static final Pattern DISAMBIG_LINK_PATTERN =
		Pattern.compile("(?:\\[\\[|\\{\\{(?:plink|ilink)\\|)([^\\]|}]+)");

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
		/**
		 * Furniture that has to be standing already rather than collected - an upgrade's previous
		 * tier. Listed among the materials, but marked with a cost of zero and no quantity.
		 */
		Set<String> prerequisites;
	}

	/**
	 * Tries each candidate page in turn, stopping at the first with a recipe. Callback fires on
	 * OkHttp's thread, never the client thread - callers must hop back via ClientThread before
	 * touching any Client API.
	 */
	public void fetchRecipes(List<String> pageNames, Consumer<List<Recipe>> callback)
	{
		fetchCandidate(pageNames, 0, callback);
	}

	private void fetchCandidate(List<String> pageNames, int index, Consumer<List<Recipe>> callback)
	{
		if (index >= pageNames.size())
		{
			callback.accept(Collections.emptyList());
			return;
		}

		fetchRecipes(pageNames.get(index), recipes ->
		{
			if (!recipes.isEmpty() || index + 1 >= pageNames.size())
			{
				callback.accept(recipes);
				return;
			}
			fetchCandidate(pageNames, index + 1, callback);
		});
	}

	private void fetchRecipes(String pageName, Consumer<List<Recipe>> callback)
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

		fetchWikitext(pageName, wikitext ->
		{
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
		});
	}

	/**
	 * The object ids a piece of furniture puts in the house, so its presence can be checked
	 * against the scene. A page lists several when one build places several objects, or when the
	 * page covers more than one variant.
	 */
	void fetchObjectIds(String pageName, Consumer<Set<Integer>> callback)
	{
		fetchWikitext(pageName, wikitext ->
			callback.accept(wikitext == null ? Collections.emptySet() : parseObjectIds(wikitext)));
	}

	static Set<Integer> parseObjectIds(String wikitext)
	{
		Set<Integer> ids = new LinkedHashSet<>();
		Matcher matcher = OBJECT_ID_PATTERN.matcher(wikitext);
		while (matcher.find())
		{
			for (String id : matcher.group(1).split(","))
			{
				try
				{
					ids.add(Integer.parseInt(id.trim()));
				}
				catch (NumberFormatException ignored)
				{
					// These fields also carry things like "N/A".
				}
			}
		}
		return ids;
	}

	/** @param callback receives the page's wikitext, or null if it couldn't be fetched. */
	private void fetchWikitext(String pageName, Consumer<String> callback)
	{
		String url = API_URL + URLEncoder.encode(pageName.replace(' ', '_'), StandardCharsets.UTF_8);
		Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Required materials: wiki lookup failed for '{}'", pageName, e);
				callback.accept(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					callback.accept(!response.isSuccessful() || body == null ? null : extractWikitext(body.string()));
				}
				catch (Exception e)
				{
					log.warn("Required materials: failed to parse wiki response for '{}'", pageName, e);
					callback.accept(null);
				}
			}
		});
	}

	private String extractWikitext(String json)
	{
		JsonObject root = gson.fromJson(json, JsonObject.class);
		JsonObject parse = root.getAsJsonObject("parse");
		return parse == null ? null : parse.getAsJsonObject("wikitext").get("*").getAsString();
	}

	// Package-private so the wikitext parsing can be tested without any network access.
	List<Recipe> parseRecipes(String wikitext)
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
	String firstDisambiguationLink(String wikitext)
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
		Set<String> prerequisites = new LinkedHashSet<>();
		for (int i = 1; i <= 10; i++)
		{
			String name = params.get("mat" + i);
			if (name == null || name.isEmpty())
			{
				continue;
			}

			String quantity = params.get("mat" + i + "quantity");
			if (quantity == null || quantity.isEmpty())
			{
				// No quantity and an explicit cost of nothing marks the previous tier of an
				// upgrade. Both are needed: a real item can omit its quantity when one is meant
				// (Cat blanket's bolt of cloth), and it carries no cost field at all.
				if ("0".equals(params.get("mat" + i + "cost")))
				{
					prerequisites.add(name);
				}
				else
				{
					materials.put(name, 1);
				}
				continue;
			}

			try
			{
				materials.put(name, Integer.parseInt(quantity.trim()));
			}
			catch (NumberFormatException ignored)
			{
			}
		}

		List<String> levelRequirements = new ArrayList<>();
		addLevelRequirement(levelRequirements, params, "skill1", "skill1lvl");
		addLevelRequirement(levelRequirements, params, "skill2", "skill2lvl");

		return new Recipe(params.get("output1subtxt"), materials, levelRequirements, prerequisites);
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
