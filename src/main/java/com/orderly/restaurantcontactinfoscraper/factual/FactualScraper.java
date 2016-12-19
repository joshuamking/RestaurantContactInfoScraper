package com.orderly.restaurantcontactinfoscraper.factual;

import com.factual.driver.*;
import com.orderly.restaurantcontactinfoscraper.Coordinates;
import com.orderly.restaurantcontactinfoscraper.FileUtils;
import com.orderly.restaurantcontactinfoscraper.GeoBlock;
import javafx.util.Pair;
import org.apache.activemq.artemis.utils.json.JSONArray;
import org.apache.activemq.artemis.utils.json.JSONException;
import org.apache.activemq.artemis.utils.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created on 5/2/16.
 * <p>
 * http://www.darrinward.com/lat-long/?id=1935749
 */
public class FactualScraper {
	public static final String HEADER                 = "name,phone,address,locality,region,country,zip,website,email,factual_page,latitude,longitude";
	public static final String KEY                    = "WynD7vY8DuZXFSsQng6ePIsaT3pPUgDx5TwZV41f";
	public static final int    LIMIT_RECORDS_PER_PAGE = 50;
	public static final int    MAX_RECORDS_PER_BLOCK  = 500;
	public static final String NEW_LINE               = "\n";
	public static final String QUOTE                  = "\"";
	public static final String SECRET                 = "tqP4PIK5oKIpRF5mRxRJJI1IDrUp8gXI4fKIPUjl";
	private static File usaCoordinatesDoneDrillInFile;

	public static void main (String[] args) throws Exception {
		if (args.length == 2 && args[1].toLowerCase().equals("usa")) {
			pullUSA(args);
		} else if (args.length < 5) {
			System.out.println(
					"USAGE: java -jar FactualScraper.jar <output_directory> <city> <state> <country> <area_size_in_miles> <should_open_file_when_done: optional, default is " +
					"false>");
			System.out.println("        -- OR --");
			System.out.println("       java -jar FactualScraper.jar <output_directory> USA");
			exit();
		} else { pullCity(args); }
	}

	private static void pullUSA (String[] args) throws Exception {
		String outputDir = args[0];
		int blockSize = 15;
		List<Coordinates> coordinatesInUS = SplitUSIntoCoordinates.getCoordinatesInUS();
		File usaCoordinatesDoneFile = new File(outputDir + "/coordinates_done_usa.txt");
		usaCoordinatesDoneDrillInFile = new File(outputDir + "/coordinates_drill_in_done_usa.txt");

		if (usaCoordinatesDoneFile.exists()) {
			coordinatesInUS.removeAll(
					Files.readAllLines(usaCoordinatesDoneFile.toPath()).stream().filter(line -> line.trim().length() > 0).map(Coordinates::parse).collect(Collectors.toList()));
		}

		System.out.printf("Getting Factual data for the continental United States");
		System.out.println();
		Set<JSONObject> jsonObjects = new HashSet<>();

		// Username: jking@siftit.com
		// Password: TheOrderlyPassword

		Factual factual = new Factual(KEY, SECRET);
		double blockSizeInCoordsApprox = milesToCoordsApprox(blockSize);

		String throttleInfo = "";
		System.out.println();
		new File(outputDir).mkdirs();
		usaCoordinatesDoneDrillInFile.createNewFile();
		File outputFile = new File(outputDir + "/usa_" + String.valueOf(System.currentTimeMillis()) + ".csv");
		FileUtils.createAndWriteToFile(outputFile, HEADER, false);

		for (Coordinates coordinates : coordinatesInUS) {
			GeoBlock block = new GeoBlock(coordinates, blockSizeInCoordsApprox);

			throttleInfo = getRecordsAndDrillDownInBlockAsNeeded(jsonObjects, factual, block, throttleInfo);
			String csvContent = jsonObjectsToCsv(jsonObjects);

			jsonObjects.clear();
			FileUtils.createAndWriteToFile(outputFile, csvContent, false, StandardOpenOption.APPEND);
			FileUtils.createAndWriteToFile(usaCoordinatesDoneFile, "\n" + coordinates, false, StandardOpenOption.APPEND);
		}

		printThrottleInfo(throttleInfo);
		System.out.println();
		System.out.println();

		System.out.printf("File located at %s%n", outputFile.toPath().toAbsolutePath());
		System.out.println("Done.");
	}

	private static String jsonObjectsToCsv (Set<JSONObject> jsonObjects) {
		return NEW_LINE + jsonObjects.stream().map(obj -> {
			String name = obj.optString("name").trim();
			String phone = obj.optString("tel").trim();
			String address = obj.optString("address").trim();
			String locality = obj.optString("locality").trim();
			String region = obj.optString("region").trim();
			String country = obj.optString("country").trim();
			String zip = obj.optString("postcode").trim();
			String website = obj.optString("website").trim();
			String email = obj.optString("email");
			String factualPageUrl = "https://www.factual.com/" + obj.optString("factual_id").trim();
			String lat = String.valueOf(obj.optDouble("latitude"));
			String lon = String.valueOf(obj.optDouble("longitude"));

			String[] csvLineData = new String[]{name, phone, address, locality, region, country, zip, website, email, factualPageUrl, lat, lon};
			return Arrays.stream(csvLineData)
						 .map(cell -> QUOTE + cell.replace(QUOTE, QUOTE + QUOTE) + QUOTE)
						 .reduce((cell1, cell2) -> cell1.trim().concat(",").concat(cell2.trim()))
						 .orElse("")
						 .trim()
						 .replaceAll("\\n", "\t\t");
		}).filter(s -> s.trim().length() > 0).reduce((row1, row2) -> row1.concat(NEW_LINE).concat(row2)).orElse("");
	}

	private static void pullCity (String[] args) throws Exception {
		String outputDir = args[0];
		String city = args[1];
		String state = args[2];
		String country = args[3];
		int totalBlockSizeMiles = Integer.parseInt(args[4]);
		boolean openWhenDone = args.length < 6 ? false : Boolean.parseBoolean(args[5]);

		int blockSize = totalBlockSizeMiles < 15 ? totalBlockSizeMiles : 15;
		int numberOfColumns = totalBlockSizeMiles / blockSize;
		int minNumberOfBlocks = numberOfColumns * numberOfColumns;

		System.out.printf("Getting Factual data for restaurants in a %dx%d mile block around the center of %s, %s, %s and saving it in \"%s\"%n", totalBlockSizeMiles,
						  totalBlockSizeMiles, city, state, country, outputDir);

		Optional<Pair<Double, Double>> coordinatesOptional = getCoordinatesByCityInfo(city, state, country);

		if (coordinatesOptional.isPresent()) {
			Pair<Double, Double> coordinates = coordinatesOptional.get();
			double origCenterLat = coordinates.getKey();
			double originalCenterLon = coordinates.getValue();

			Set<JSONObject> jsonObjects = new HashSet<>();

			// Username: jking@siftit.com
			// Password: TheOrderlyPassword

			Factual factual = new Factual(KEY, SECRET);
			double milesInCoordsApprox = milesToCoordsApprox(totalBlockSizeMiles / 2);
			double blockSizeInCoordsApprox = milesToCoordsApprox(blockSize);

			double upperLeftLat = origCenterLat + milesInCoordsApprox;
			double upperLeftLon = originalCenterLon - milesInCoordsApprox;

			String throttleInfo = "";
			System.out.println();

			for (int i = 0; i < minNumberOfBlocks; i++) {
				int rowsCompleted = i / numberOfColumns;
				int indexOnThisRow = i - rowsCompleted * numberOfColumns;
				double latFrom = upperLeftLat - blockSizeInCoordsApprox * rowsCompleted;
				double lonFrom = upperLeftLon + blockSizeInCoordsApprox * indexOnThisRow;

				GeoBlock block = new GeoBlock(new Coordinates(latFrom, lonFrom), blockSizeInCoordsApprox);

				updateProgress(i, minNumberOfBlocks);
				throttleInfo = getRecordsAndDrillDownInBlockAsNeeded(jsonObjects, factual, block, throttleInfo);
			}

			updateProgress(minNumberOfBlocks, minNumberOfBlocks);
			printThrottleInfo(throttleInfo);
			System.out.println("Writing to file...");
			System.out.println();

			String csvContent = HEADER + jsonObjectsToCsv(jsonObjects);

			new File(outputDir).mkdirs();
			File outputFile = new File(outputDir + "/" + city + "_" + state + "_" + String.valueOf(System.currentTimeMillis()) + ".csv");
			FileUtils.createAndWriteToFile(outputFile, csvContent, openWhenDone);

			System.out.println("Done.");
		} else {
			System.out.println("City not found. Exiting.");
			exit();
		}
	}

	private static String getRecordsAndDrillDownInBlockAsNeeded (Set<JSONObject> jsonObjects, Factual factual, GeoBlock block, String throttleInfo)
			throws InterruptedException, JSONException {
		Pair<Integer, String> numberOfRecordsGatheredAndThrottleInfoPair = tryToAddRecordsToSetWithinBlock(jsonObjects, factual, block, throttleInfo);
		int recordsGatheredForBlock = numberOfRecordsGatheredAndThrottleInfoPair.getKey();
		throttleInfo = numberOfRecordsGatheredAndThrottleInfoPair.getValue();
		throttleInfo = drillDownInBlockIfNeeded(jsonObjects, factual, throttleInfo, block, recordsGatheredForBlock);
		return throttleInfo;
	}

	private static String drillDownInBlockIfNeeded (Set<JSONObject> jsonObjects, Factual factual, String throttleInfo, GeoBlock block, int recordsGatheredForBlock)
			throws InterruptedException, JSONException {
		if (recordsGatheredForBlock == MAX_RECORDS_PER_BLOCK) {
			double newBlockSizeInCoords = Math.abs((block.ul.lat - block.lr.lat) / 2);
			double origLatFrom = block.ul.lat;
			double origLonFrom = block.ul.lon;

			int numOfColumns = 2;
			for (int i = 0; i < numOfColumns * numOfColumns; i++) {
				int rowsCompleted = i / numOfColumns;
				int indexOnThisRow = i - rowsCompleted * numOfColumns;
				block.ul.lat = origLatFrom - (rowsCompleted * newBlockSizeInCoords);
				block.ul.lon = origLonFrom + (indexOnThisRow * newBlockSizeInCoords);
				block.lr.lat = origLatFrom - (rowsCompleted * newBlockSizeInCoords + newBlockSizeInCoords);
				block.lr.lon = origLonFrom + (indexOnThisRow * newBlockSizeInCoords + newBlockSizeInCoords);

				try {
					FileUtils.createAndWriteToFile(usaCoordinatesDoneDrillInFile, "\n" + block, false, StandardOpenOption.APPEND);
				} catch (IOException ignored) {
				}

				Pair<Integer, String> numberOfRecordsGatheredAndThrottleInfoPair = tryToAddRecordsToSetWithinBlock(jsonObjects, factual, block, throttleInfo);
				recordsGatheredForBlock = numberOfRecordsGatheredAndThrottleInfoPair.getKey();
				throttleInfo = numberOfRecordsGatheredAndThrottleInfoPair.getValue();
				throttleInfo = drillDownInBlockIfNeeded(jsonObjects, factual, throttleInfo, block, recordsGatheredForBlock);
			}
		}
		return throttleInfo;
	}

	private static Pair<Integer, String> tryToAddRecordsToSetWithinBlock (Set<JSONObject> jsonObjects, Factual factual, GeoBlock block, String throttleInfo)
			throws InterruptedException, JSONException {
		try {
			int recordsGatheredForBlock = 0;
			for (int j = 0; j < MAX_RECORDS_PER_BLOCK; j += LIMIT_RECORDS_PER_PAGE) {
				Shape rectangle = block.toRectangle();
				Query query = new Query().field("email")
										 .notBlank()
										 .field("category_labels")
										 .includesAny("SOCIAL", "FOOD AND DINING", "RESTAURANTS", "BARS")
										 .within(rectangle)
										 .limit(LIMIT_RECORDS_PER_PAGE)
										 .offset(j);
				Thread.sleep(500);      // To help with the burst and expensive throttle

				ReadResponse readResponse = factual.fetch("restaurants-us", query);
				throttleInfo = readResponse.getRawResponse().getHeaders().getFirstHeaderStringValue("X-Factual-Throttle-Allocation");

				String jsonAsString = readResponse.getJson();
				JSONObject obj = new JSONObject(jsonAsString);
				JSONObject response = obj.getJSONObject("response");
				int numberOfRecordsReturnedForPage = response.optInt("included_rows", LIMIT_RECORDS_PER_PAGE);
				recordsGatheredForBlock += numberOfRecordsReturnedForPage;
				JSONArray objArray = response.getJSONArray("data");
				for (int k = 0; k < objArray.length(); k++) {
					jsonObjects.add(objArray.getJSONObject(k));
				}
				if (numberOfRecordsReturnedForPage != LIMIT_RECORDS_PER_PAGE) { break; }
			}
			return new Pair<>(recordsGatheredForBlock, throttleInfo);
		} catch (FactualApiException e) {
			e.printStackTrace();
			printThrottleInfo(throttleInfo);
			if (e.getStatusCode() == 403 && !e.getMessage().toLowerCase().contains("please simplify your query")) { exit(); } else { restart(); }
			return new Pair<>(0, throttleInfo);
		}
	}

	private static double milesToCoordsApprox (double miles) {
		return miles / 69.1;
	}

	private static void printThrottleInfo (String throttleInfo) {
		System.out.println();
		System.out.println();
		try {
			System.out.printf("API usage for this 24-hour period is at %s%n", new JSONObject(throttleInfo).getString("daily"));
		} catch (JSONException ignored) {
		}
		System.out.println(throttleInfo);
		System.out.println();
	}

	/**
	 * x out of y
	 */
	private static void updateProgress (int x, int y) {
		double progressPercentage = x / y;
		final int width = 150; // progress bar width in chars

		System.out.printf("\r%d/%d -- %s%% [", x, y, (float) Math.round(progressPercentage * 1000) / 10);
		int i = 0;
		for (; i <= (int) (progressPercentage * width); i++) {
			System.out.print("=");
		}
		for (; i < width; i++) {
			System.out.print(" ");
		}
		System.out.print("]");
	}

	private static void exit () {
		System.exit(1);
	}

	private static void restart () {
		System.exit(2);
	}

	public static Optional<Pair<Double, Double>> getCoordinatesByCityInfo (String city, String state, String country) {
		try {
			//	https://maps.googleapis.com/maps/api/geocode/json?address=cumming,ga,usa
			String key = "AIzaSyB0ObIHD41ZiTIQv6P8dwWx4L1h8jA1Jug";
			String url = urlEscapeSpace(String.format("https://maps.googleapis.com/maps/api/geocode/json?key=%s&address=<%s,%s,%s>", key, city, state, country));
			String response = getResponseBody(url);

			JSONObject obj = new JSONObject(response);
			if (!obj.getString("status").equalsIgnoreCase("OK")) {
				throw new Exception("Return Status was NOT \"OK\"!\n\n" + response);
			}
			JSONObject res = obj.getJSONArray("results").getJSONObject(0);
			JSONObject loc = res.getJSONObject("geometry").getJSONObject("location");
			double lat = loc.getDouble("lat");
			double lon = loc.getDouble("lng");

			return Optional.of(new Pair<>(lat, lon));
		} catch (Exception e) {
			e.printStackTrace();
			return Optional.empty();
		}
	}

	private static String urlEscapeSpace (String url) {
		return url.replaceAll(" ", "%20");
	}

	private static String getResponseBody (String url) {
		try {
			URLConnection connection = new URL(url).openConnection();
			BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
			String inputLine;

			String response = "";
			while ((inputLine = in.readLine()) != null) { response += inputLine; }
			in.close();
			return response;
		} catch (Exception e) {
			e.printStackTrace();
			return "";
		}
	}
}