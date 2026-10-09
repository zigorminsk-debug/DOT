import com.example.motioncalm.DotPhysics;

/** Моделирует поездку: разгон 2 с (+2 м/с² вперёд), ход 3 с, торможение 2 с (-3 м/с²). */
public class PhysicsTest {
    public static void main(String[] args) {
        DotPhysics p = new DotPhysics();
        float dt = 0.02f;   // 50 Гц, как SENSOR_DELAY_GAME
        float maxY = 0, minY = 0;
        System.out.println("t(с)   a(м/с²)  смещение Y(dp)  скорость Y");
        for (int i = 0; i <= 350; i++) {
            float t = i * dt;
            float a;
            if (t < 2) a = 2f;          // разгон: вперёд по экрану = uy отрицательный
            else if (t < 5) a = 0f;     // равномерно
            else if (t < 7) a = -3f;    // торможение
            else a = 0f;
            // ускорение вперёд у телефона в портрете -> экранный uy < 0
            p.step(0f, -a, dt);
            maxY = Math.max(maxY, p.offsetY());
            minY = Math.min(minY, p.offsetY());
            if (i % 25 == 0) {
                System.out.printf("%5.2f  %6.2f  %12.2f  %10.2f%n", t, a, p.offsetY(), 0f);
            }
        }
        System.out.printf("max смещение Y: %.2f dp, min: %.2f dp%n", maxY, minY);
    }
}
