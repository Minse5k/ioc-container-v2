package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.Component;
import com.ll.standard.util.Ut;
import org.reflections.Reflections;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * IoC 컨테이너 클론 v2 — 컴포넌트 스캔.
 * <p>
 * init() 에서 basePackage 아래의 @Component(및 @Service·@Repository·@Configuration 처럼
 * @Component 가 붙은 애너테이션) 클래스를 reflections 로 스캔해 빈 정의(빈 이름 -> 클래스)를 모은다.
 * 빈 이름은 클래스 이름의 첫 글자를 소문자로 바꾼 것이다.
 * 빈은 생성자 파라미터 타입으로 의존 빈을 알아내 먼저 만든 뒤 주입하고, 한 번 만든 빈은 싱글톤으로 보관한다.
 */
public class ApplicationContext {
    private final String basePackage;

    // 빈 이름 -> 빈 클래스 (스캔 결과)
    private final Map<String, Class<?>> beanDefinitions = new HashMap<>();
    // 빈 이름 -> 만들어진 빈(싱글톤)
    private final Map<String, Object> beans = new HashMap<>();
    // 지금 만들고 있는 빈 이름(순환 의존 감지용)
    private final Set<String> beansInCreation = new HashSet<>();

    public ApplicationContext(String basePackage) {
        this.basePackage = basePackage;
    }

    public void init() {
        scanComponents();

        // 모든 빈을 미리 만들어 둔다.
        beanDefinitions.keySet().forEach(this::genBean);
    }

    private void scanComponents() {
        Reflections reflections = new Reflections(basePackage);

        reflections.getTypesAnnotatedWith(Component.class).stream()
                .filter(this::isConcreteClass)
                .forEach(cls -> {
                    String beanName = Ut.str.lcfirst(cls.getSimpleName());
                    Class<?> prev = beanDefinitions.put(beanName, cls);

                    if (prev != null && prev != cls) {
                        throw new IllegalStateException("빈 이름이 겹칩니다: %s (%s, %s)".formatted(beanName, prev.getName(), cls.getName()));
                    }
                });
    }

    // @Service 같은 애너테이션 타입, 인터페이스, 추상 클래스는 빈으로 만들지 않는다.
    private boolean isConcreteClass(Class<?> cls) {
        return !cls.isAnnotation()
                && !cls.isInterface()
                && !Modifier.isAbstract(cls.getModifiers());
    }

    @SuppressWarnings("unchecked")
    public <T> T genBean(String beanName) {
        Object bean = beans.get(beanName);
        if (bean != null) return (T) bean;

        Class<?> beanClass = beanDefinitions.get(beanName);
        if (beanClass == null) {
            throw new IllegalArgumentException("등록되지 않은 빈입니다: " + beanName);
        }

        if (!beansInCreation.add(beanName)) {
            throw new IllegalStateException("순환 의존이 있습니다: " + beanName);
        }

        try {
            bean = createBean(beanClass);
            beans.put(beanName, bean);
            return (T) bean;
        } finally {
            beansInCreation.remove(beanName);
        }
    }

    private Object createBean(Class<?> beanClass) {
        Constructor<?> constructor = findInjectableConstructor(beanClass);

        // 생성자 파라미터 타입마다 그 타입의 빈을 먼저 얻어서 주입한다.
        Object[] args = Arrays.stream(constructor.getParameterTypes())
                .map(this::genBeanByType)
                .toArray();

        try {
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("빈 생성 실패: " + beanClass.getName(), e);
        }
    }

    // 생성자가 하나면 그것을, 여러 개면 파라미터가 가장 많은 것을 쓴다.
    private Constructor<?> findInjectableConstructor(Class<?> beanClass) {
        return Arrays.stream(beanClass.getDeclaredConstructors())
                .max((a, b) -> Integer.compare(a.getParameterCount(), b.getParameterCount()))
                .orElseThrow();
    }

    private Object genBeanByType(Class<?> type) {
        String beanName = beanDefinitions.entrySet().stream()
                .filter(e -> type.isAssignableFrom(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("주입할 빈이 없습니다: " + type.getName()));

        return genBean(beanName);
    }
}
