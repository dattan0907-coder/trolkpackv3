# Troll Pack (NeoForge 1.21.1 + GeckoLib)

Port của LittleRoom Troll Pack. Toàn bộ AI/skill viết bằng Java thuần (không cần MythicMobs).

## Build jar bằng GitHub (không cần cài gì)
1. Tạo repository mới → upload toàn bộ thư mục này (tổng < 100 file).
   Nếu thư mục `.github` không lên, chọn *Add file → Create new file*, gõ `.github/workflows/build.yml` và dán nội dung file đó.
2. Vào tab **Actions** → *Build jar* → chạy xong tải artifact **trollpack-jar**.
3. Bỏ `trollpack-1.0.0.jar` + **GeckoLib 4.7.x (NeoForge 1.21.1)** vào thư mục `mods`.

## Skill / hành vi
| Mob | Skill |
|---|---|
| Troll / Aggro | combo 2 đòn (đòn 2 hất tung), ném giáo, nhảy đập (sóng bụi), **war cry** buff cả bầy, ngồi / ngủ / **tìm đồ ăn**, có animation đứng dậy |
| Elite | combo + đá, nhảy đập sóng xung kích, **lao húc (charge)** tông văng mục tiêu, **rage** dưới 40% HP, nhảy múa khi đưa táo vàng |
| Voodoo | sọ ma tự dẫn (làm chậm), **bắn 3 viên** khi dưới 35% HP, **hồi máu cả bầy**, giữ khoảng cách / lùi / đi vòng |
| Chief | hào quang lửa, đổi **Troll Mask → Emerald** |
| Decor | heo nướng (khói, đập ra thịt), nồi (hơi), totem (lửa), ngai vàng (lấp lánh) |

## Di chuyển
Leo bậc 1.25 block, chạy nước rút khi đuổi, đi vòng quanh mục tiêu khi chờ đòn, nhảy có dự đoán vị trí, bụi dưới chân khi chạy,
tốc độ animation chân theo tốc độ thật, đầu quay theo hướng nhìn (không quay khi ngủ).
Các skill dùng chung "global cooldown" nên không bao giờ dùng chồng nhau.

## Cấu trúc
`src/main/java/com/trollpack/entity/TrollEntity.java` – AI + skill; `SpearProjectile.java` – giáo & sọ ma;
`client/` – model/renderer; `tools/` – script chuyển .bbmodel (không cần để build).
