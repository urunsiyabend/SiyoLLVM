# R2 Task 1 RED log

- Date: 2026-11-25 (run started 2026-11-25T10:15:49+03:00)
- HEAD: a8d52b12639be0afe55cc7a456f56dddd0a5d23a (the new cases uncommitted on top; committed as "test: erased-value use sites the reference runs (RED)")
- Command (bounded Maven wrapper, `command -v mvn` = /home/urunsiyabend/.hermes/automation/siyollvm/bin/mvn):

      mvn -Pnative test -Dtest='NativeCorpusTest,ModulesNativeTest' -Dsurefire.failIfNoSpecifiedTests=false

- Result: BUILD FAILURE, exit 1. NativeCorpusTest: 13 failures, exactly the 13 new
  corpus cases (any_for_in, any_for_in_uncaught, any_int_operand_uncaught,
  any_int_operands, any_len_uncaught, any_len_values, any_long_operand_uncaught,
  any_long_operands, any_string_builtin_arg_uncaught, any_string_builtin_args,
  any_struct_field_uncaught, any_struct_fields, map_field_struct_values).
  ModulesNativeTest: 1 failure, the new modules_array_any_elements. Every
  failure is SiyoLLVM's `[llvm] … is not supported by SiyoLLVM` diagnostic;
  no pre-existing case failed.
- Log: `native-corpus-modules-red.log`.
