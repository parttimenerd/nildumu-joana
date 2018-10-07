package edu.kit.nildumu.prog;
import edu.kit.joana.ui.annotations.Level;
import edu.kit.joana.ui.annotations.Source;
import edu.kit.nildumu.annotations.Expect;
import edu.kit.nildumu.annotations.MethodInvocationHandlersToUse;
import edu.kit.nildumu.annotations.ShouldLeak;
import static edu.kit.nildumu.ui.CodeUI.leak;
import static edu.kit.nildumu.ui.CodeUI.output;

import edu.kit.nildumu.ui.Config;
import edu.kit.nildumu.ui.EntryPoint;
import edu.kit.nildumu.ui.Value;

public class SimpleTestBed2 {

	public static void main(String[] args) {
		simpleAdd(10);
//		simple2(10);
//		simple3(10, 10);
//		simple4(10, 10);
//		simpleIf(true);
		whileLoop(10);
//		whileWithBreak(10);
//		mod(10);
//		mod2(10);
//		advancedIf(10, 10);
//		simpleFuncTest(10);
		basicFunctionCalls1(10);
		basicFib(10);
		basicFibReduced(10);
		basicDepsOnFunction(10);
		weirdLoopFunctionTermination(true, true);
//		nestedFunctionCalls(10);
//		nestedFunctionCalls2(10);
//		conditionalRecursion(10);
	}
	
//	@EntryPoint
//	@Config(intWidth=1)
//	@ShouldLeak(exactly=1, bits="0bu")
//	@Expect(bitWidth=1)
	public static void simpleAdd(@Source int h) {
		output(h + 1, "l");
	}
//	
//	@EntryPoint
//	@Config(intWidth=2)
//	@ShouldLeak(exactly=1)
//	public static void simple2(@Source int h) {
//		output(h | 1, "l");
//	}
//	
//	@EntryPoint
//	@Config(intWidth=2)
//	@ShouldLeak(exactly=2)
//	public static void simple3(@Source int h, @Source int h2) {
//		output(h | h2, "l");
//	}
//	
//	@EntryPoint
//	@Config(intWidth=2)
//	@ShouldLeak(exactly=2)
//	public static void simple4(@Source int h, @Source int h2) {
//		int a = 0b0 | h;
//		output(h | a, "l");
//	}
//	
//	@EntryPoint
//	@ShouldLeak(exactly=1)
//	public static void simpleIf(@Source boolean h) {
//		int o;
//		if (h) {
//			o = 1;
//		} else {
//			o = 0;
//		}
//		leak(o);
//	}
//	
//	@EntryPoint
//	@Config(intWidth=2)
//	@ShouldLeak(exactly=1, bits="0b0u")
	public static void advancedIf(@Source int h, @Source int h2) {
		int a = 1;
		if (h > 0) {
			a = (h | a) & 0;
		}
		output(a, "l");
	}
//	
//	@EntryPoint
//	@Config(intWidth=1)
//	@ShouldLeak(exactly=1)
	public static void whileLoop(@Source int h) {
		int o = 0;
		while (h > 0) {
			o++;
		}
		leak(o);
	}
//	
//	@EntryPoint
//	@Config(intWidth=3)
//	@ShouldLeak(exactly=0)
//	public static void whileWithBreak(@Source int h) {
//		int o = 0;
//		while (o >= 0) {
//			if (1 == 1) {
//				break;
//			}
//			o += h;
//		}
//		leak(o);
//	}
//	
//	@EntryPoint
//	@Config(intWidth=3)
//	@ShouldLeak(exactly=3)
//	public static void mod(@Source int h) {
//		leak(3 % h);
//	}
//	
//	@EntryPoint
//	@Config(intWidth=3)
//	@ShouldLeak(exactly=1)
	public static void mod2(@Source int h) {
		leak(h % 2);
	}
//	
//	@EntryPoint
//	@Config(intWidth=1)
//	@MethodInvocationHandlersToUse(useDefaultHandlers=true)
//	@ShouldLeak(exactly=1)
//	public static void simpleFuncTest(@Source(level=Level.HIGH) int h) {
//		output(run(h, h | 0) | 0, "l");
//	}
//	
//	public static int run(int a, int b) {
//		return a | b;
//	}
//	
//	@EntryPoint
//	@Config(intWidth=2)
//	@MethodInvocationHandlersToUse("summary")
//	@ShouldLeak(exactly=2, bits="0buu")
	public static void basicFunctionCalls1(@Source int h) {
		leak(_1_bla(h));
	}
	
	public static int _1_bla(int a) {
		return a;
	}
////	
//	@EntryPoint
//	@Config(intWidth=3)
//	@MethodInvocationHandlersToUse({"call_string"})
//	@ShouldLeak(exactly=0)
	public static void basicFib(@Source @Value("0b0u") int h) {
		int r = 0;
		if (1 != fib(2)) {
			if (1 == fib(h)) {
				r = fib(h);
			}
		}
		leak(r);
	}
	
	public static int fib(int a) {
		int r = 1;
		if (a == 1){
			r = fib(a);
		}
		return r;
	}
	
//	@EntryPoint
//	@Config(intWidth=5)
//	@MethodInvocationHandlersToUse(exclude= {"all"})
//	@ShouldLeak(exactly=4)
//	@Expect(bitWidth=5) // TODO: new test case
	public static void basicFibReduced(@Source @Value("0b0uuuu") int h) {
		leak(fib_r(h));
	}
	
	public static int fib_r(int a) {
		int r = 1;
		if (a == 1){
			r = a;
		}
		return r;
	}
//	
//	@EntryPoint
//	@Config(intWidth=2)
//	@MethodInvocationHandlersToUse({"call_string"})
//	@ShouldLeak(exactly=1)
	public static void basicDepsOnFunction(@Source @Value("0buu") int h) {
		leak(_2_bla(h));
	}
//	
	public static int _2_bla(int a) {
		return _2_bla(a) | 1;
	}
//	
//	
	@EntryPoint
	@MethodInvocationHandlersToUse("all")
	@ShouldLeak(exactly=1)
	public static void weirdLoopFunctionTermination(@Source @Value("0bu") boolean h, @Source(level="l") boolean l) {
	    boolean res = true; 
		if (l) {
			res = false;
	    }
		leak(id(h));
	}
	
	public static boolean id(boolean a) {
        return a;
    }
//	
//	@EntryPoint
//	@Config(intWidth=2)
//	@MethodInvocationHandlersToUse
//	@ShouldLeak(exactly=2)
//	public static void nestedFunctionCalls(@Source @Value("0buu") int h) {
//		leak(_4_f(h));
//	}
//	
//	public static int _4_f(int a) {
//		return _4_h(a);
//	}
//	
//	public static int _4_h(int a) {
//		return _4_g(a);
//	}
//	
//	public static int _4_g(int a) {
//		return a;
//	}
//	
//	@EntryPoint
//	@Config(intWidth=3)
//	@MethodInvocationHandlersToUse
//	@ShouldLeak(exactly=3)
//	public static void conditionalRecursion(@Source @Value("0buuu") int h) {
//		leak(_5_f(h, 0, 0, 0, 0, 4));
//	}
//	
//	public static int _5_f(int x, int y, int z, int w, int v, int l) {
//         int r = 0;
//         if (l == 0) {
//            r = v;
//         } else {
//            r = _5_f(0, x, y, z, w, l+0b111);
//         }
//         return r;
//     }
//	
//	@EntryPoint
//	@Config(intWidth=2)
//	@MethodInvocationHandlersToUse
//	@ShouldLeak(exactly=2)
//	public static void nestedFunctionCalls2(@Source @Value("0buu") int h) {
//		leak(_4_f2(h));
//	}
//	
//	public static int _4_f2(int a) {
//		return _4_h2(a) & _4_h2(a) & _4_h2(a);
//	}
//	
//	public static int _4_h2(int a) {
//		return _4_g2(a) & _4_g2(a) & _4_g2(a);
//	}
//	
//	public static int _4_g2(int a) {
//		return _4_i2(a) & _4_i2(a) & _4_i2(a);
//	}
//	
//	public static int _4_i2(int a) {
//		return a;
//	}
}
