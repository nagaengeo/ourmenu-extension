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
            Document doc = Jsoup.connect(url).userAgent("Mozilla/5.0").get();

            LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");
            WriteBatch batch = db.batch();
            int count = 0;

            Elements rows = doc.select("tr");
            String currentRestaurant = "Unknown";

            for (Element row : rows) {
                String text = row.text().trim();

                // 1. 식당 이름 감지
                if (row.select("div.rest-ico").first() != null ||
                        text.contains("학생식당") || text.contains("숭실도담") ||
                        text.contains("FACULTY LOUNGE") || text.contains("스넥코너") || text.contains("푸드코트")) {

                    currentRestaurant = text.replace("!", "").replace("★", "").trim();
                    continue;
                }

                Elements cells = row.children();
                if (cells.size() < 13 || !cells.get(0).hasClass("menu-list-corn")) continue;
                String corner = cells.get(0).text().trim();

                for (int i = 2; i <= 12; i += 2) {
                    Element cell = cells.get(i);

                    // 2. 줄바꿈 태그를 구분자로 치환 후 리스트로 분리
                    String rawHtml = cell.html()
                            .replaceAll("(?i)<br[^>]*>", "@@")
                            .replaceAll("(?i)</?(div|p|tr|table)[^>]*>", "@@");

                    List<String> lines = Arrays.stream(Jsoup.parse(rawHtml).text().split("@@"))
                            .map(line -> line.replace("★", "").trim()) // 별표 제거 및 공백 정리
                            .filter(line -> !line.isEmpty())
                            .collect(Collectors.toList());

                    if (lines.isEmpty() || String.join("", lines).contains("등록된 메뉴가 없습니다")) continue;

                    // 3. 리스트를 콤마(,)로 연결하여 상세 정보 생성
                    String fullText = String.join(", ", lines);

                    // 4. 가격 및 메뉴명 분리
                    String price = "-";
                    String menuName = lines.get(0); // 기본값은 첫 줄
                    Matcher priceMatcher = Pattern.compile("-\\s*([0-9]+\\.[0-9]+)").matcher(fullText);
                    if (priceMatcher.find()) {
                        price = priceMatcher.group(1);
                        menuName = fullText.substring(0, priceMatcher.start()).replace(", ", " ").trim();
                    }
                    menuName = menuName.replaceAll("\\[.*?\\]", "").trim(); // [추천메뉴] 등 제거

                    // 5. 알러지 및 원산지 정보 정제 (콤마 처리 포함)
                    String allergy = cleanInfo(extractInfo(fullText, "*알러지유발식품:", "*원산지:"));
                    String origin = cleanInfo(extractInfo(fullText, "*원산지:", null));

                    String targetDate = monday.plusDays((i / 2) - 1).format(fmt);

                    Map<String, Object> data = new HashMap<>();
                    data.put("date", targetDate);
                    data.put("restaurant", currentRestaurant);
                    data.put("corner", corner);
                    data.put("menu_name", menuName);
                    data.put("price", price);
                    data.put("details", fullText); // 반찬들이 콤마로 연결됨
                    data.put("allergy", allergy);
                    data.put("origin", origin);
                    data.put("updated_at", com.google.cloud.Timestamp.now());

                    String docId = targetDate + "_" + currentRestaurant.replaceAll(" ", "") + "_" + corner.replaceAll(" ", "");
                    batch.set(db.collection("weekly_menus").document(docId), data);
                    count++;
                }
            }

            if (count > 0) {
                batch.commit().get();
                System.out.println("총 " + count + "건 저장 성공");
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static String extractInfo(String text, String startKey, String endKey) {
        if (!text.contains(startKey)) return "-";
        try {
            int start = text.indexOf(startKey) + startKey.length();
            int end = (endKey != null && text.contains(endKey)) ? text.indexOf(endKey) : text.length();
            return text.substring(start, end).trim();
        } catch (Exception e) { return "-"; }
    }

    // 정보 내 불필요한 콤마나 공백 추가 정제
    private static String cleanInfo(String text) {
        if (text.equals("-")) return text;
        return text.startsWith(", ") ? text.substring(2) : text;
    }
}