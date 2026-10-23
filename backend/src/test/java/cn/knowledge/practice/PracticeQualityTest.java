package cn.knowledge.practice;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PracticeQualityTest {
    @Test void clearArrayExplanationIsUsableButRawTranscriptionAndNavigationAreNot() {
        assertThat(PracticeQuality.usableSource("使用快慢指针删除数组中的指定值。fast 遍历原数组，slow 表示下一个保留元素的写入位置。遇到不等于目标值的元素时，将其写入 arr[slow]，然后 slow 自增。最后返回 slow，有效元素位于原数组的前 slow 个位置，实际数组长度不变。" )).isTrue();
        assertThat(PracticeQuality.usableSource("那么接下来我们来看一下代码，写上一个注释，所以我们接着把这个东西放进去然后继续下去最后就这样结束了这个过程。" )).isFalse();
        assertThat(PracticeQuality.usableSource("# 无标题\n[导航](https://example.com/"+"a".repeat(80)+")" )).isFalse();
        assertThat(PracticeQuality.usableSource("这个位置继续往后移动".repeat(30))).isFalse();
        assertThat(PracticeQuality.usableSource("原始转写（未审校，不用于出题）\n快指针遍历数组，慢指针标记下一个可写位置。遇到非目标元素才写入，返回有效元素个数，原数组长度不变。" )).isFalse();
    }

    @Test void sourceCodeDoesNotCountAsRunOnProse() {
        assertThat(PracticeQuality.usableSource("快指针遍历数组，慢指针标记下一个可写位置。遇到非目标元素才写入，返回有效元素个数，原数组长度不变。\n```java\n// "+"说明".repeat(90)+"\n```" )).isTrue();
    }

    @Test void referencePointsMustBeReadableSingleParagraphsWithoutCircularReasons() {
        assertThat(PracticeQuality.clearOutput("返回 slow，表示有效元素个数；原数组的实际长度不变。",180)).isTrue();
        assertThat(PracticeQuality.clearOutput("List<String> 表示元素类型为 String 的泛型列表。",180)).isTrue();
        assertThat(PracticeQuality.clearOutput("构造方法不能被重写，因为构造方法不能被重写。",180)).isFalse();
        assertThat(PracticeQuality.clearOutput("先执行父类构造器，\n再执行子类剩余代码。",180)).isFalse();
        assertThat(PracticeQuality.clearOutput("哥们写上AR点上4i就OK。",180)).isFalse();
    }

    @Test void inventedEvidenceCannotBeMatchedAndRealQuotesPreserveOriginalCharacters() {
        String original="父类初始化完成后，再执行子类构造器的**剩余代码**。";
        assertThat(PracticeQuality.originalQuote(original,"再执行子类构造器的剩余代码。")).isEqualTo("再执行子类构造器的**剩余代码**。");
        assertThat(PracticeQuality.originalQuote(original,"先执行子类构造器的剩余代码。")).isNull();
    }

    @Test void formattingToleranceNeverDropsCodeOperatorsOrChangesGenericTypes() {
        String code="结果为 a * b，表示 a 与 b 相乘；List<String> 保留泛型类型。";
        assertThat(PracticeQuality.originalQuote(code,"结果为 a b，表示 a 与 b 相乘；")).isNull();
        assertThat(PracticeQuality.originalQuote(code,"List<Integer> 保留泛型类型。")).isNull();
        assertThat(PracticeQuality.originalQuote(code,"结果为 a * b，表示 a 与 b 相乘；")).isEqualTo("结果为 a * b，表示 a 与 b 相乘；");
    }

    @Test void questionsNeedAnExplicitSubjectAndAnswersCannotCopyAWholeParagraph() {
        assertThat(PracticeQuality.standaloneQuestion("该算法的返回值是什么？")).isFalse();
        assertThat(PracticeQuality.standaloneQuestion("删除数组目标值时，slow指针的含义是什么？")).isTrue();
        assertThat(PracticeQuality.concisePoint("fast和slow的初值均为0。")).isTrue();
        assertThat(PracticeQuality.concisePoint("先遍历数组。再写入元素。最后返回个数。")).isFalse();
        assertThat(PracticeQuality.concisePoint("说明".repeat(61))).isFalse();
    }

    @Test void shortNumericFactsUseTheQuestionSubjectWithoutChangingTheirValue() {
        assertThat(PracticeQuality.referencePoint("slow的初始值是多少？","0")).isEqualTo("slow的初始值是0。");
        assertThat(PracticeQuality.referencePoint("合法下标的最小值为多少？","0")).isEqualTo("合法下标的最小值为0。");
        assertThat(PracticeQuality.referencePoint("子类先初始化哪一部分？","父类")).isEqualTo("父类");
    }
}
