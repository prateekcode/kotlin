// ISSUE: KT-52276

interface IrDeclarationWithName { val name: String }

class IrField(override val name: String) : IrDeclarationWithName

fun test(field: IrField) {
    <!VAL_REASSIGNMENT!>field.name<!> = "value"
}
