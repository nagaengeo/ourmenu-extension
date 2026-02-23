import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;

public class MenuCrawler {
    public static void main(String[] args) {
        // 1. 크롤링 타겟 URL (숭실대 생협 식단 페이지)
        String url = "https://soongguri.com/main.php?mkey=2&w=3&l=1";

        try {
            // 2. Jsoup을 이용해 HTML 문서 가져오기
            // userAgent는 봇 차단을 방지하기 위해 일반 브라우저처럼 보이게 설정합니다.
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .get();

            // 3. 식당 이름 추출 (td 태그 중 클래스가 rest_nm인 요소들)
            // 학생식당, 숭실도담, FACULTY LOUNGE, 스넥코너 등이 잡힙니다.
            Elements restaurantSections = doc.select("td.rest_nm");

            for (Element restNm : restaurantSections) {
                String restaurantName = restNm.text();
                System.out.println("\n📍 식당: " + restaurantName);
                System.out.println("----------------------------------------------");

                // 4. 식당명 바로 아래 행(tr)에 메뉴 정보가 들어있습니다.
                // HTML 구조: <tr><td>식당명</td></tr> <tr><td>메뉴정보</td></tr>
                Element menuRow = restNm.parent().nextElementSibling();

                if (menuRow == null) continue;

                // 5. 메뉴가 없는 경우 ("오늘 등록된 메뉴가 없습니다.") 체크
                Elements noMenu = menuRow.select("td.rest_nomn");
                if (!noMenu.isEmpty()) {
                    System.out.println("정보: " + noMenu.text());
                    continue;
                }

                // 6. 메뉴 이미지 및 텍스트 추출
                // 이미지 태그 중 경로가 /menu/menu_file/로 시작하는 요소를 찾습니다.
                Elements menuImages = menuRow.select("img[src^=/menu/menu_file/]");

                if (menuImages.isEmpty()) {
                    // 이미지가 없는 경우 텍스트만이라도 가져옵니다.
                    String plainTextMenu = menuRow.text();
                    System.out.println("메뉴(텍스트): " + plainTextMenu);
                } else {
                    for (Element img : menuImages) {
                        // 이미지의 절대 경로 생성
                        String imgUrl = "https://soongguri.com" + img.attr("src");

                        // 이미지 태그의 부모의 이전 형제 노드에 메뉴 이름과 가격이 있는 경우가 많습니다.
                        // 파싱이 까다로운 경우 텍스트 전체에서 필요한 부분만 추출합니다.
                        Element infoArea = img.parent().previousElementSibling();
                        String menuInfo = (infoArea != null) ? infoArea.text() : "메뉴명 확인 필요";

                        System.out.println("🍴 메뉴: " + menuInfo);
                        System.out.println("📸 이미지: " + imgUrl);
                    }
                }
                System.out.println("----------------------------------------------");
            }

        } catch (IOException e) {
            // 네트워크 연결 실패 시 로그 출력
            System.err.println("크롤링 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }
}