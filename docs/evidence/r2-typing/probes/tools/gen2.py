import os, sys
out = sys.argv[1]
PRE = open(os.path.join(out, 'b_trim__ok.siyo')).read().split('\nfn probe')[0] + '\n'
probes = {
 'op_add_long_str': ('println(a + 1L)', ('5L', '"x"', '5L')),
 'op_add_int_on_long': ('println(a + 1)', ('5', '5L', '5')),
 'op_mul_long': ('println(a * 2L)', ('5L', '"x"', 'null')),
 'op_div_long': ('println(a / 2L)', ('5L', '"x"', 'null')),
 'op_lt_int_left': ('println(1 < a)', ('5', '"x"', 'null')),
 'op_eq_string': ('println(a == "abc")', ('"abc"', '5', 'null')),
 'op_eq_long': ('println(a == 5L)', ('5L', '5', 'null')),
 'm_read_typed': ('imut p: Point = a\n    imut n: int = p.x\n    println(n + 1)', ('Point { x: 1, y: 2 }', '"s"', 'null')),
 'i_array_on_map': ('println(a[0])', ('[7]', '{"k": 1}', 'null')),
}
for name, (body, vals) in probes.items():
    for v, val in zip(('ok', 'wrong', 'null'), vals):
        with open(os.path.join(out, f'{name}__{v}.siyo'), 'w') as f:
            f.write(f"{PRE}\nfn probe(a: any) {{\n    {body}\n}}\n\nfn main() {{\n    probe({val})\n}}\n")
