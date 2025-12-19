package org.example.ticketrzdtracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.ticketrzdtracker.model.TicketResult;
import org.example.ticketrzdtracker.model.TrainSessionData;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RzdService {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private String currentCookies = null;

    private static final String TRAIN_PRICING_URL = "https://ticket.rzd.ru/api/v1/railway-service/prices/train-pricing";
    private static final String SUGGEST_URL = "https://ticket.rzd.ru/api/v1/suggests";
    private static final String MAIN_PAGE_URL = "https://ticket.rzd.ru/";
    private static final String API_CAR_PRICING_URL = "https://ticket.rzd.ru/apib2b/p/Railway/V1/Search/CarPricing?service_provider=B2B_RZD&isBonusPurchase=false";

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public RzdService() {
        this.mapper = new ObjectMapper();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    // 1. Инициализация сессии (как в твоем ParseOne)
    public synchronized void initSession() {
        if (currentCookies != null) return;
        log.info("🔄 Инициализация новой сессии и получение Cookies...");
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MAIN_PAGE_URL))
                    .GET()
                    .header("User-Agent", USER_AGENT)
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

            StringBuilder sb = new StringBuilder();
            response.headers().allValues("Set-Cookie").forEach(c -> sb.append(c.split(";")[0]).append("; "));
            sb.append("acceptCookies=1; LANG_SITE=ru; x-client-id=22900;");

            this.currentCookies = sb.toString();
            log.info("✅ Cookies получены");
        } catch (Exception e) {
            log.error("❌ Ошибка инициализации сессии", e);
        }
    }

    // 2. Поиск кода станции (Логика из ParseOne)
    public String findStationCode(String cityName) {
        initSession();
        try {
            String encodedName = URLEncoder.encode(cityName, StandardCharsets.UTF_8);
            // Используем только Query, без TransportType (так надежнее)
            String queryParams = "Query=" + encodedName + "&GroupResults=true&RailwaySortPriority=true&SynonymOn=1&Language=ru";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SUGGEST_URL + "?" + queryParams))
                    .GET()
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.rzd.ru/")
                    .header("Origin", "https://www.rzd.ru")
                    .header("Accept", "application/json")
                    .header("Cookie", currentCookies)
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("❌ Ошибка поиска станции {}. Code: {}", cityName, response.statusCode());
                return null;
            }

            JsonNode root = mapper.readTree(response.body());
            String foundCode = null;

            if (root.isObject()) {
                for (JsonNode group : root) {
                    if (group.isArray() && group.size() > 0) {
                        foundCode = extractCodeFromNode(group.get(0));
                        if (foundCode != null) break;
                    }
                }
            } else if (root.isArray() && root.size() > 0) {
                foundCode = extractCodeFromNode(root.get(0));
            }

            if (foundCode != null) {
                log.info("✅ Станция {} -> {}", cityName, foundCode);
                return foundCode;
            }
            log.warn("⚠️ Станция {} не найдена в ответе.", cityName);
            return null;

        } catch (Exception e) {
            log.error("Ошибка поиска станции", e);
            return null;
        }
    }

    private String extractCodeFromNode(JsonNode node) {
        if (node.has("expressCode")) return String.valueOf(node.get("expressCode").asInt());
        if (node.has("code")) return node.get("code").asText(); // fallback
        return null;
    }

    // 3. Поиск поезда (Логика из ParseOne)
    public TrainSessionData searchFirstTrain(String originCode, String destCode, String dateStr) {
        initSession();
        try {
            // Конвертируем дату дд.мм.гггг -> ISO
            String dateIso = convertDate(dateStr);
            if (dateIso == null) return null;

            Map<String, String> params = new HashMap<>();
            params.put("service_provider", "B2B_RZD");
            params.put("getByLocalTime", "true");
            params.put("carGrouping", "DontGroup");
            params.put("origin", originCode);
            params.put("destination", destCode);
            params.put("departureDate", dateIso);
            params.put("carIssuingType", "Passenger");
            params.put("getTrainsFromSchedule", "true");

            String queryString = params.entrySet().stream()
                    .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TRAIN_PRICING_URL + "?" + queryString))
                    .GET()
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", currentCookies)
                    .header("x-client-id", "22900")
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());
            JsonNode trains = root.path("Trains");

            if (trains.isEmpty()) {
                log.info("Trains list is empty for date {}", dateIso);
                return null;
            }

            JsonNode firstTrain = trains.get(0);
            String trainNum = firstTrain.path("DisplayTrainNumber").asText();
            String exactDepartureDate = firstTrain.path("DepartureDateTime").asText();
            String provider = firstTrain.has("Provider") ? firstTrain.path("Provider").asText() : "P1";

            log.info("✅ Найден поезд: {} ({})", trainNum, exactDepartureDate);
            return new TrainSessionData(originCode, destCode, exactDepartureDate, trainNum, provider);

        } catch (Exception e) {
            log.error("Ошибка поиска поезда", e);
            return null;
        }
    }

    public List<TicketResult> getCarPricing(TrainSessionData data) {
        return getCarPricingInternal(data, 0); // Начинаем с 0 попыток
    }

    // 4. Проверка мест (Логика из RzdParser)
    private List<TicketResult> getCarPricingInternal(TrainSessionData data, int retryCount) {
        initSession();
        List<TicketResult> foundSeats = new ArrayList<>();

        String jsonBody = String.format("{"
                        + "\"OriginCode\": \"%s\","
                        + "\"DestinationCode\": \"%s\","
                        + "\"Provider\": \"%s\","
                        + "\"CarIssuingType\": \"Passenger\","
                        + "\"DepartureDate\": \"%s\","
                        + "\"HasPlacesForLargeFamily\": false,"
                        + "\"SpecialPlacesDemand\": \"StandardPlacesAndForDisabledPersons\","
                        + "\"TrainNumber\": \"%s\""
                        + "}",
                data.getOriginCode(),
                data.getDestinationCode(),
                data.getProvider(),
                data.getDepartureDate(),
                data.getTrainNumber()
        );

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_CAR_PRICING_URL))
                    .timeout(Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://ticket.rzd.ru/")
                    .header("Origin", "https://ticket.rzd.ru")
                    .header("x-client-id", "22900")
                    .header("Cookie", currentCookies)
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                JsonNode cars = root.path("Cars");

                for (JsonNode car : cars) {
                    String typeName = car.path("CarTypeName").asText();
                    String placeName = car.path("CarPlaceNameRu").asText();

                    boolean isPlats = typeName.equals("ПЛАЦ") || car.path("CarType").asText().equals("PLATS");
                    boolean isLower = placeName.equals("Нижнее");

                    if (isPlats && isLower) {
                        String carNum = car.path("CarNumber").asText();
                        String freePlaces = car.path("FreePlaces").asText();
                        double price = car.path("MinPrice").asDouble(0.0);

                        log.info("🔥 Найдено! Вагон {} ({}): {}", carNum, placeName, freePlaces);

                        String[] seats = freePlaces.split(",");
                        for (String s : seats) {
                            try {
                                int seatNum = Integer.parseInt(s.replaceAll("[^0-9]", ""));
                                foundSeats.add(new TicketResult(carNum, seatNum, price));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            } else if (response.statusCode() == 403 || response.statusCode() == 401) {
                if (retryCount < 1) { // Если это первая ошибка
                    log.warn("🔐 Сессия истекла (403). Пробую обновить куки и повторить запрос...");
                    this.currentCookies = null; // Сброс
                    initSession(); // Получаем свежие
                    return getCarPricingInternal(data, retryCount + 1); // Рекурсивный повтор
                } else {
                    log.error("❌ Не удалось восстановить сессию после повторной попытки.");
                }
            } else {
                log.error("Ошибка API мест: {}", response.statusCode());
            }

        } catch (Exception e) {
            log.error("Ошибка запроса мест", e);
        }
        return foundSeats;
    }

    // Хелпер для даты
    private String convertDate(String userInputDate) {
        try {
            DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
            LocalDate date = LocalDate.parse(userInputDate, inputFormatter);
            return date.atStartOfDay().toString();
        } catch (Exception e) {
            log.error("Неверный формат даты: {}", userInputDate);
            return null;
        }
    }
}