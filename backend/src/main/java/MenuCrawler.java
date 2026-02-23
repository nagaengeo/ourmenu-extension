import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteBatch;
import com.google.cloud.firestore.WriteResult;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import com.google.auth.oauth2.GoogleCredentials;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.FileInputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MenuCrawler {
    public static void main(String[] args) {
        try {
            FileInputStream serviceAccount = new FileInputStream("src/main/resources/serviceAccountKey.json");
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();
            if (FirebaseApp.getApps().isEmpty()) FirebaseApp.initializeApp(options);
            Firestore db = FirestoreClient.getFirestore();

            String url = "https://soongguri.com/main.php?mkey=2&w=3&l=2";
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .get();

            LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");
            WriteBatch batch = db.batch();
            int count = 0;

            Elements rows = doc.select("tr");
            String currentRestaurant = "Unknown";

            for (Element row : rows) {
                String rowText = row.text().trim();

                // 1. 식당 이름 감지
                if (row.select("div.rest-ico").first() != null ||
                        rowText.contains("학생식당") || rowText.contains("숭실도담") ||
                        rowText.contains("FACULTY LOUNGE") || rowText.contains("스넥코너") || rowText.contains("푸드코트")) {
                    currentRestaurant = rowText.replace("!", "").replace("★", "").trim();
                    continue;
                }

                Elements cells = row.children();
                if (cells.size() < 13 || !cells.get(0).hasClass("menu-list-corn")) continue;

                // 코너명 정제 및 석식 제외 로직
                String rawCorner = cells.get(0).text().trim();
                if (rawCorner.contains("석식") || rawCorner.contains("저녁")) continue; // 석식 코너는 아예 스킵

                String corner = rawCorner.contains("점심") ? "점심" : rawCorner; // 점심 1코너 -> 점심으로 저장

                for (int i = 2; i <= 12; i += 2) {
                    Element cell = cells.get(i);

                    // 사진 크롤링 (실제 음식 사진만 추출)
                    String imgUrl = "";
                    Elements imgs = cell.select("img");
                    for (Element img : imgs) {
                        String src = img.attr("src");
                        // 추천 메뉴 아이콘이 아닌 실제 메뉴 파일 경로만 필터링
                        if (src.contains("/menu/menu_file/")) {
                            imgUrl = "https://soongguri.com" + src;
                            break;
                        }
                    }

                    // 데이터 리스트화 (중복 및 공백 제거)
                    String rawHtml = cell.html()
                            .replaceAll("(?i)<br[^>]*>", "@@")
                            .replaceAll("(?i)</?(div|p|tr|table)[^>]*>", "@@");

                    List<String> lines = Arrays.stream(Jsoup.parse(rawHtml).text().split("@@"))
                            .map(line -> line.replace("★", "").trim())
                            .filter(line -> !line.isEmpty()) // 빈 줄 제거
                            .distinct() // 중복 행 제거
                            .collect(Collectors.toList());

                    if (lines.isEmpty() || String.join("", lines).contains("등록된 메뉴가 없습니다")) continue;

                    String fullText = String.join(" ", lines);

                    // 가격 및 메인 메뉴명 추출
                    String price = "";
                    String menuName = lines.get(0);
                    Matcher priceMatcher = Pattern.compile("([0-9]\\.[0-9])").matcher(fullText);
                    if (priceMatcher.find()) {
                        price = priceMatcher.group(1);
                        menuName = lines.get(0).split("-")[0].trim();
                    }
                    menuName = menuName.replaceAll("\\[.*?\\]", "").trim();

                    // 알러지/원산지 정보 추출 (없으면 빈 문자열)
                    String allergy = extractInfo(fullText, "*알러지유발식품:", "*원산지:");
                    String origin = extractInfo(fullText, "*원산지:", null);

                    // Effectively Final 변수 선언 (람다용)
                    final String finalMenuName = menuName;
                    final String finalPrice = price;

                    // details 필드 정제 (순수 반찬 리스트만 저장)
                    String finalDetails = lines.stream()
                            .filter(l -> !l.contains(finalMenuName))
                            .filter(l -> !l.contains(finalPrice))
                            .filter(l -> !l.startsWith("*"))
                            .filter(l -> !l.matches("^[a-zA-Z\\s&,]*$")) // 영어 번역 제외
                            .collect(Collectors.joining(", "));

                    String targetDate = monday.plusDays((i / 2) - 1).format(fmt);

                    // 데이터 맵 생성 및 배치 추가
                    Map<String, Object> data = new HashMap<>();
                    data.put("date", targetDate);
                    data.put("restaurant", currentRestaurant);
                    data.put("corner", corner);
                    data.put("menu_name", menuName);
                    data.put("price", price);
                    data.put("details", finalDetails);
                    data.put("allergy", allergy);
                    data.put("origin", origin);
                    data.put("img_url", imgUrl); // 사진 URL 추가
                    data.put("updated_at", com.google.cloud.Timestamp.now());

                    String docId = targetDate + "_" + currentRestaurant.replaceAll(" ", "") + "_" + corner.replaceAll(" ", "");
                    batch.set(db.collection("weekly_menus").document(docId), data);
                    count++;
                }
            }

            if (count > 0) {
                batch.commit().get();
                System.out.println("사진 포함 최종 정제 데이터 " + count + "건 업로드 완료!");
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static String extractInfo(String text, String startKey, String endKey) {
        if (!text.contains(startKey)) return "";
        try {
            int start = text.indexOf(startKey) + startKey.length();
            int end = (endKey != null && text.contains(endKey)) ? text.indexOf(endKey) : text.length();
            String result = text.substring(start, end).trim();
            if (result.equals("-") || result.equals(",")) return "";
            return result.endsWith(",") ? result.substring(0, result.length() - 1).trim() : result;
        } catch (Exception e) { return ""; }
    }
}