import os, sys
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
PRE = """struct Point { x: int, y: int }
impl Point {
    fn sum(self) -> int { self.x + self.y }
}
fn takeS(s: string) { println(s + "!") }
fn takeI(n: int) { println(n + 1) }
fn takeL(n: long) { println(n + 1L) }
"""
S = ('"abc"', '5', 'null')
I = ('5', '"x"', 'null')
L = ('5L', '5', 'null')
P = ('Point { x: 1, y: 2 }', '"s"', 'null')
A = ('[1, 2, 3]', '5', 'null')
M = ('{"k": 1, "j": 2}', '[1]', 'null')
probes = {
 'b_parseInt': ('println(parseInt(a))', ('"42"', '42', 'null')),
 'b_len_str': ('println(len(a))', S),
 'b_contains_arg1': ('println(contains(a, "b"))', S),
 'b_contains_arg2': ('println(contains("abc", a))', ('"b"', '5', 'null')),
 'b_substring': ('println(substring(a, 1, 2))', S),
 'b_trim': ('println(trim(a))', S),
 'b_toUpper': ('println(toUpper(a))', S),
 'b_split': ('println(split(a, "b"))', S),
 'b_startsWith': ('println(startsWith(a, "a"))', S),
 'b_indexOf': ('println(indexOf(a, "c"))', S),
 'f_string_param': ('takeS(a)', S),
 'f_int_param': ('takeI(a)', I),
 'f_long_param': ('takeL(a)', L),
 'op_add_int': ('println(a + 1)', I),
 'op_add_int_left': ('println(1 + a)', I),
 'op_add_long': ('println(a + 1L)', L),
 'op_add_string': ('println(a + "x")', S),
 'op_add_string_left': ('println("x" + a)', S),
 'op_sub_int': ('println(a - 1)', I),
 'op_mul_int': ('println(a * 2)', I),
 'op_div_int': ('println(a / 2)', I),
 'op_sub_long': ('println(a - 1L)', L),
 'op_lt_int': ('println(a < 6)', I),
 'op_lt_long': ('println(a < 6L)', L),
 'op_lt_string': ('println(a < "b")', S),
 'op_eq_int': ('println(a == 5)', I),
 'op_pluseq_int_target': ('mut n = 1\n    n += a\n    println(n)', I),
 'op_pluseq_long_target': ('mut n = 1L\n    n += a\n    println(n)', L),
 'op_pluseq_string_target': ('mut s = "x"\n    s += a\n    println(s)', S),
 'op_pluseq_any_target': ('mut b: any = a\n    b += 1\n    println(b)', I),
 'm_read': ('imut p: Point = a\n    println(p.x)', P),
 'm_read_arith': ('imut p: Point = a\n    println(p.x + 1)', P),
 'm_write': ('imut p: Point = a\n    p.x = 7\n    println(p)', P),
 'm_pluseq': ('imut p: Point = a\n    p.x += 1\n    println(p.x)', P),
 'm_direct_read': ('println(a.x)', P),
 'm_method': ('imut p: Point = a\n    println(p.sum())', P),
 'm_method_direct': ('println(a.sum())', P),
 'i_array': ('println(a[1])', A),
 'i_array_arith': ('println(a[1] + 1)', A),
 'i_map': ('println(a["k"])', M),
 'for_array': ('for x in a { println(x) }', A),
 'for_map': ('for k in a { println(k) }', M),
 'len_array': ('println(len(a))', A),
 'len_map': ('println(len(a))', M),
}
for name, (body, vals) in probes.items():
    for v, val in zip(('ok', 'wrong', 'null'), vals):
        with open(os.path.join(out, f'{name}__{v}.siyo'), 'w') as f:
            f.write(f"{PRE}\nfn probe(a: any) {{\n    {body}\n}}\n\nfn main() {{\n    probe({val})\n}}\n")
