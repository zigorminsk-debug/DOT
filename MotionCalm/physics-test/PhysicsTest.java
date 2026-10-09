import com.example.motioncalm.DotPhysics;

/**
 * Ручная проверка физики точек без Android. Моделирует поездку: разгон 2 с (+2 м/с² вперёд),
 * ход 3 с, торможение 2 с (-3 м/с²), затем покой до 20 с. Проверяет, что точки сдвинулись,
 * не вышли за предел и вернулись в центр.
 *
 * Запуск из папки MotionCalm (нужен JDK 17+):
 *   javac -encoding UTF-8 -d build/physics app/src/main/java/com/example/motioncalm/DotPhysics.java physics-test/PhysicsTest.java
 *   java -cp build/physics PhysicsTest
 * Код выхода 0 означает, что проверки пройдены.
 */
public class PhysicsTest {
    public static void main(String[] args) {
        DotPhysics p = new DotPhysics();
        float dt = 0.02f;                  // 50 Гц, как SENSOR_DELAY_GAME
        float maxAbs = 0f;
        boolean finite = true;

        System.out.println("t(с)   a(м/с²)  смещение Y(dp)  скорость Y(dp/с)");
        for (int i = 0; i <= 1000; i++) {
            float t = i * dt;
            float a;
            if (t < 2) a = 2f;             // разгон вперёд
            else if (t < 5) a = 0f;        // равномерное движение
            else if (t < 7) a = -3f;       // торможение
            else a = 0f;                   // покой
            // Вперёд по ходу в портретной ориентации соответствует uy < 0 на экране
            p.step(0f, -a, dt);
            float off = p.offsetY();
            if (Float.isNaN(off) || Float.isInfinite(off)) {
                finite = false;
            }
            maxAbs = Math.max(maxAbs, Math.abs(off));
            if (i % 50 == 0) {
                System.out.printf("%5.2f  %6.2f  %14.2f  %15.2f%n", t, a, off, p.velocityY());
            }
        }
        float endOffset = p.offsetY();
        System.out.printf("max |смещение Y|: %.2f dp, в конце: %.3f dp%n", maxAbs, endOffset);

        boolean moved = maxAbs > 10f;                             // точки реально сдвинулись
        boolean bounded = maxAbs <= DotPhysics.BASE_MAX + 1e-3f;  // не вышли за предел
        boolean returned = Math.abs(endOffset) < 1f;              // вернулись в центр
        if (finite && moved && bounded && returned) {
            System.out.println("Проверка пройдена");
        } else {
            System.out.println("Проверка НЕ пройдена: finite=" + finite + " moved=" + moved
                    + " bounded=" + bounded + " returned=" + returned);
            System.exit(1);
        }
    }
}
