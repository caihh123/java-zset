import org.example.ZSet;

public class ZSetTest {
    public static class Value implements Comparable<Value> {
        int age;
        int id;
        public Value(int age, int id) {
            this.age = age;
            this.id = id;
        }

        @Override
        public int compareTo(Value o) {
            if (age != o.age) {
                return age - o.age;
            }
            return id - o.id;
        }

        public String toString() {
            return "age: " + age + ", id: " + id;
        }
    }
    public static void main(String[] args) {
        ZSet<Integer, Value> zSet = new ZSet();
        for (int i = 0; i < 10; i++) {
            zSet.update(i+1, new Value(i, i*10));
        }

        for (int i = 0; i < 10; i++) {
            System.out.println("key: " + i + " rank: " + zSet.rank(i+1));
        }
        for (int i = 0; i < 10; i++) {
            Value value = zSet.get(i+1);
            System.out.println("key: " + i + " item: " + value.toString());
        }

    }
}
