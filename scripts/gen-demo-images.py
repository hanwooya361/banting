"""체험 동네 시드 공구(T09.5)용 상품 일러스트를 그린다. 결과: frontend/public/demo/*.webp

직접 그린 단순 도형이라 라이선스 걱정이 없다. 실제 사진으로 바꿀 때는 같은 파일 이름으로 덮어쓰면 된다.
실행: python scripts/gen-demo-images.py  (Pillow 필요)
"""
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 800
SCALE = 2  # 2배로 그린 뒤 줄여서 가장자리를 부드럽게
OUT = Path(__file__).resolve().parent.parent / "frontend" / "public" / "demo"

INK = (60, 60, 70)
WHITE = (255, 255, 255)


def canvas(bg):
    img = Image.new("RGB", (SIZE * SCALE, SIZE * SCALE), bg)
    return img, ImageDraw.Draw(img)


def s(*values):
    """좌표를 SCALE 배로."""
    return [v * SCALE for v in values]


def save(img, name):
    OUT.mkdir(parents=True, exist_ok=True)
    img.resize((SIZE, SIZE), Image.LANCZOS).save(OUT / f"{name}.webp", "WEBP", quality=85)


def tissue():
    img, d = canvas((222, 236, 250))
    # 위에서 본 두루마리 3 x 2
    for row in range(2):
        for col in range(3):
            cx, cy = 200 + col * 200, 290 + row * 220
            d.ellipse(s(cx - 95, cy - 95, cx + 95, cy + 95), fill=WHITE, outline=(200, 210, 225), width=6 * SCALE)
            d.ellipse(s(cx - 32, cy - 32, cx + 32, cy + 32), fill=(214, 190, 160))
            d.ellipse(s(cx - 18, cy - 18, cx + 18, cy + 18), fill=(150, 125, 100))
    save(img, "tissue")


def water():
    img, d = canvas((214, 242, 246))
    for i in range(4):
        x = 130 + i * 150
        d.rounded_rectangle(s(x, 250, x + 120, 660), radius=40 * SCALE, fill=(170, 215, 240),
                            outline=(110, 170, 210), width=5 * SCALE)
        d.rounded_rectangle(s(x + 30, 200, x + 90, 260), radius=14 * SCALE, fill=(110, 170, 210))
        d.rectangle(s(x + 35, 170, x + 85, 205), fill=(40, 110, 190))
        d.rectangle(s(x, 400, x + 120, 480), fill=(40, 110, 190))
        d.rounded_rectangle(s(x + 18, 290, x + 34, 380), radius=8 * SCALE, fill=(220, 240, 252))
    save(img, "water")


def kitchen_towel():
    img, d = canvas((252, 243, 214))
    for i in range(2):
        x = 220 + i * 210
        d.rectangle(s(x, 210, x + 160, 640), fill=WHITE, outline=(215, 205, 180), width=5 * SCALE)
        d.ellipse(s(x, 180, x + 160, 240), fill=WHITE, outline=(215, 205, 180), width=5 * SCALE)
        d.ellipse(s(x + 55, 197, x + 105, 223), fill=(190, 160, 120))
        for k in range(5):  # 물결무늬 대신 점선 절취선
            y = 290 + k * 75
            for j in range(6):
                d.rectangle(s(x + 12 + j * 25, y, x + 26 + j * 25, y + 5), fill=(235, 180, 90))
    save(img, "kitchen-towel")


def detergent():
    img, d = canvas((218, 245, 232))
    for i in range(4):
        x = 110 + i * 150
        top = 260 + (i % 2) * 30
        d.rounded_rectangle(s(x, top, x + 130, 650), radius=30 * SCALE, fill=(80, 170, 200))
        d.polygon(s(x + 20, top, x + 110, top, x + 90, top - 50, x + 40, top - 50), fill=(80, 170, 200))
        d.rectangle(s(x + 50, top - 80, x + 80, top - 45), fill=(250, 200, 60))
        d.rounded_rectangle(s(x + 18, top + 120, x + 112, top + 230), radius=16 * SCALE, fill=WHITE)
        d.ellipse(s(x + 45, top + 150, x + 85, top + 190), fill=(120, 200, 240))
    save(img, "detergent")


def chicken():
    img, d = canvas((253, 230, 218))
    for i in range(3):
        y = 470 - i * 110
        x = 170 + (i % 2) * 30
        d.rounded_rectangle(s(x, y, x + 430, y + 150), radius=26 * SCALE, fill=WHITE, outline=(230, 200, 190),
                            width=5 * SCALE)
        d.rounded_rectangle(s(x + 40, y + 30, x + 390, y + 120), radius=40 * SCALE, fill=(244, 186, 170))
        d.rounded_rectangle(s(x + 70, y + 50, x + 250, y + 100), radius=24 * SCALE, fill=(250, 210, 196))
        d.rectangle(s(x + 300, y + 40, x + 370, y + 70), fill=(80, 170, 120))
    save(img, "chicken")


def zipper_bag():
    img, d = canvas((234, 228, 250))
    for i in range(3):
        o = i * 40
        x0, y0 = 170 + o, 200 + o
        d.rounded_rectangle(s(x0, y0, x0 + 420, y0 + 400), radius=24 * SCALE, fill=(245, 245, 255),
                            outline=(170, 160, 210), width=5 * SCALE)
        d.rectangle(s(x0 + 20, y0 + 40, x0 + 400, y0 + 48), fill=(220, 70, 80))
        d.rectangle(s(x0 + 20, y0 + 56, x0 + 400, y0 + 64), fill=(60, 110, 210))
    save(img, "zipper-bag")


def battery():
    img, d = canvas((254, 236, 210))
    for i in range(5):
        x = 120 + i * 120
        d.rectangle(s(x + 30, 200, x + 70, 230), fill=(170, 170, 175))
        d.rounded_rectangle(s(x, 225, x + 100, 640), radius=18 * SCALE, fill=(40, 40, 48))
        d.rounded_rectangle(s(x, 225, x + 100, 360), radius=18 * SCALE, fill=(240, 150, 40))
        d.rectangle(s(x, 330, x + 100, 360), fill=(240, 150, 40))
        d.polygon(s(x + 55, 420, x + 35, 500, x + 52, 500, x + 45, 560, x + 68, 470, x + 52, 470), fill=(250, 210, 70))
    save(img, "battery")


def wet_wipes():
    img, d = canvas((226, 244, 220))
    for i in range(2):
        y = 430 - i * 150
        x = 160 + i * 40
        d.rounded_rectangle(s(x, y, x + 440, y + 190), radius=60 * SCALE, fill=WHITE, outline=(190, 215, 185),
                            width=5 * SCALE)
        d.rounded_rectangle(s(x + 140, y - 20, x + 300, y + 50), radius=22 * SCALE, fill=(110, 190, 120))
        d.ellipse(s(x + 40, y + 90, x + 110, y + 160), fill=(200, 235, 200))
    save(img, "wet-wipes")


if __name__ == "__main__":
    for draw in (tissue, water, kitchen_towel, detergent, chicken, zipper_bag, battery, wet_wipes):
        draw()
    print("완료:", sorted(p.name for p in OUT.glob("*.webp")))
