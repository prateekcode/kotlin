/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.atomicfu.compiler.backend.jvm

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.jvm.ir.fileParent
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.util.capitalizeDecapitalize.capitalizeAsciiOnly
import org.jetbrains.kotlinx.atomicfu.compiler.backend.*
import org.jetbrains.kotlinx.atomicfu.compiler.backend.addProperty
import org.jetbrains.kotlinx.atomicfu.compiler.backend.buildClassInstance
import org.jetbrains.kotlinx.atomicfu.compiler.backend.capture
import kotlin.collections.set

private const val AFU_PKG = "kotlinx.atomicfu"
private const val LOCKS = "locks"
private const val AFU_LOCKS_PKG = "$AFU_PKG.$LOCKS"
private const val REENTRANT_LOCK_TYPE = "ReentrantLock"
private const val TRACE_BASE_TYPE = "TraceBase"
private const val GET = "get"
private const val GET_VALUE = "getValue"
private const val SET_VALUE = "setValue"
private const val ATOMIC_VALUE_FACTORY = "atomic"
private const val TRACE = "Trace"
private const val INVOKE = "invoke"
private const val APPEND = "append"
private const val ATOMIC_ARRAY_OF_NULLS_FACTORY = "atomicArrayOfNulls"
private const val REENTRANT_LOCK_FACTORY = "reentrantLock"

private const val ATOMICFU = "atomicfu"
private const val ATOMIC_ARRAY_RECEIVER_SUFFIX = "\$array"
private const val DISPATCH_RECEIVER = "${ATOMICFU}\$dispatchReceiver"
private const val ATOMIC_HANDLER = "${ATOMICFU}\$handler"
private const val ACTION = "${ATOMICFU}\$action"
private const val INDEX = "${ATOMICFU}\$index"
private const val VOLATILE_WRAPPER_SUFFIX = "\$VolatileWrapper"
private const val LOOP = "loop"
private const val UPDATE = "update"

class AtomicfuJvmIrTransformer(
    private val context: IrPluginContext,
    private val atomicSymbols: AtomicSymbols
) {

    private val irBuiltIns = context.irBuiltIns

    private val AFU_VALUE_TYPES: Map<String, IrType> = mapOf(
        "AtomicInt" to irBuiltIns.intType,
        "AtomicLong" to irBuiltIns.longType,
        "AtomicBoolean" to irBuiltIns.booleanType,
        "AtomicRef" to irBuiltIns.anyNType
    )

    private val ATOMICFU_INLINE_FUNCTIONS = setOf("loop", "update", "getAndUpdate", "updateAndGet")
    protected val ATOMIC_VALUE_TYPES = setOf("AtomicInt", "AtomicLong", "AtomicBoolean", "AtomicRef")
    protected val ATOMIC_ARRAY_TYPES = setOf("AtomicIntArray", "AtomicLongArray", "AtomicBooleanArray", "AtomicArray")

    private fun analyzeAtomicFields(moduleFragment: IrModuleFragment) {
        moduleFragment.files.forEach { irFile ->
            irFile.transform(AtomicHandlerTransformer(), null)

            irFile.patchDeclarationParents()
        }
    }

    private fun transformAtomicExtensions(moduleFragment: IrModuleFragment) {
        moduleFragment.files.forEach { irFile ->
            irFile.transform(AtomicExtensionTransformer(), null)
        }
    }

    fun transform(moduleFragment: IrModuleFragment) {
        analyzeAtomicFields(moduleFragment)
        transformAtomicExtensions(moduleFragment)
        moduleFragment.files.forEach { irFile ->
            irFile.transform(AtomicFunctionsTransformer(), null)

            irFile.declarations.filterIsInstance<IrClass>().forEach {
                it.patchDeclarationParents(it.parent)
            }
        }
        // todo check that no AFU is left
    }

    private val propertyToAtomicHandler = mutableMapOf<IrProperty, IrProperty>()

    private inner class AtomicHandlerTransformer : IrElementTransformer<IrFunction?> {
        override fun visitClass(declaration: IrClass, data: IrFunction?): IrStatement {
            declaration.declarations.filter(::isAtomic).forEach { atomicProperty ->
                if ((atomicProperty as IrProperty).isDelegated) {
                    atomicProperty.transformDelegatedProperty(declaration)
                } else {
                    atomicProperty.transformAtomicProperty(declaration)
                }
            }
            declaration.declarations.filter(::isAtomicArray).forEach { atomicArrayProperty ->
                (atomicArrayProperty as IrProperty).transformAtomicArrayProperty(declaration)
            }
            declaration.declarations.removeIf(::isTrace)
            return super.visitClass(declaration, data)
        }

        override fun visitFile(declaration: IrFile, data: IrFunction?): IrFile {
            declaration.declarations.filter { isAtomic(it) || isAtomicArray(it) }.forEach { atomicProperty ->
                val volatileWrapperClass = buildVolatileWrapperClass(atomicProperty as IrProperty, declaration).also {
                    context.addProperty(
                        field = context.buildClassInstance(it, declaration),
                        parent = declaration,
                        isStatic = true
                    )
                }
                if (isAtomic(atomicProperty)) {
                    if (atomicProperty.isDelegated) {
                        atomicProperty.transformDelegatedProperty(volatileWrapperClass)
                    } else {
                        atomicProperty.transformAtomicProperty(volatileWrapperClass)
                        declaration.declarations.remove(atomicProperty)
                        volatileWrapperClass.declarations.add(atomicProperty)
                        atomicProperty.parent = volatileWrapperClass
                    }
                } else {
                    atomicProperty.transformAtomicArrayProperty(volatileWrapperClass)
                    declaration.declarations.remove(atomicProperty)
                    volatileWrapperClass.declarations.add(atomicProperty)
                    atomicProperty.parent = volatileWrapperClass
                }
            }
            declaration.declarations.removeIf(::isTrace)
            return super.visitFile(declaration, data)
        }

        private fun IrProperty.transformAtomicProperty(parentClass: IrClass) {
            backingField = buildVolatileRawField(this, parentClass)
            context.addDefaultGetter(this, parentClass)
            registerAtomicHandler(addAtomicFUProperty(this, parentClass))
        }

        private fun IrProperty.transformAtomicArrayProperty(parentClass: IrClass) {
            backingField = buildJucaArrayField(this, parentClass)
            context.addDefaultGetter(this, parentClass)
            registerAtomicHandler(this)
        }

        private fun IrProperty.transformDelegatedProperty(parentClass: IrClass) {
            backingField?.let { backingField ->
                backingField.initializer?.let {
                    val initializer = it.expression as IrCall
                    if (initializer.isAtomicFactory()) {
                        // val a by atomic(0)
                        val volatileField = buildVolatileRawField(this, parentClass)
                        this.backingField = volatileField
                        getter?.transformAccessor(volatileField, getter?.dispatchReceiverParameter?.capture())
                        setter?.transformAccessor(volatileField, setter?.dispatchReceiverParameter?.capture())
                    } else {
                        // val _a = atomic(0)
                        // val a by _a
                        val atomicProperty = initializer.getCorrespondingProperty()
                        val volatileField = atomicProperty.backingField!!
                        this.backingField = null
                        if (atomicProperty.isWrapped()) {
                            atomicSymbols.createBuilder(symbol).run {
                                val parent = getStaticVolatileWrapperInstance(atomicProperty)
                                getter?.transformAccessor(volatileField, getProperty(parent, null))
                                setter?.transformAccessor(volatileField, getProperty(parent, null))
                            }
                        } else {
                            if (this.parent == atomicProperty.parent) {
                                getter?.transformAccessor(volatileField, getter?.dispatchReceiverParameter?.capture())
                                setter?.transformAccessor(volatileField, setter?.dispatchReceiverParameter?.capture())
                            } else {
                                val thisReceiver = atomicProperty.parentAsClass.thisReceiver
                                getter?.transformAccessor(volatileField, thisReceiver?.capture())
                                setter?.transformAccessor(volatileField, thisReceiver?.capture())
                            }
                        }
                    }
                }
            }
        }

        private fun IrFunction.transformAccessor(volatileField: IrField, parent: IrExpression?) {
            val accessor = this
            atomicSymbols.createBuilder(symbol).run {
                body = irExprBody(
                    irReturn(
                        if (accessor.isGetter) {
                            irGetField(parent, volatileField)
                        } else {
                            irSetField(parent, volatileField, accessor.valueParameters[0].capture())
                        }
                    )
                )
            }
        }

        private fun IrProperty.registerAtomicHandler(atomicHandlerProperty: IrProperty) {
            propertyToAtomicHandler[this] = atomicHandlerProperty
        }

        private fun buildVolatileRawField(atomicProperty: IrProperty, parentClass: IrClass) =
            atomicProperty.backingField?.let { atomicField ->
                getPropertyInitializer(atomicField, parentClass)?.let {
                    val initializer = (it as IrCall).getValueArgument(0)?.deepCopyWithSymbols()
                        ?: error("Atomic factory should take at least one argument: $this")
                    val valueType = atomicField.type.atomicToValueType()
                    context.irFactory.buildField {
                        name = atomicProperty.name
                        type = if (valueType.isBoolean()) irBuiltIns.intType else valueType
                        isFinal = false
                        visibility = atomicField.visibility
                    }.apply {
                        this.initializer = IrExpressionBodyImpl(initializer)
                        annotations = atomicField.annotations + atomicSymbols.volatileAnnotationConstructorCall
                    }
                } ?: error("Atomic property ${atomicProperty.render()} should be initialized either directly or in the init{} block")
            } ?: error("Atomic property ${atomicProperty.render()} expected non null backingField")

        private fun addAtomicFUProperty(atomicProperty: IrProperty, parentClass: IrClass): IrProperty {
            atomicProperty.backingField?.let { volatileField ->
                val fieldUpdaterClass = atomicSymbols.getFieldUpdaterClass(volatileField.type)
                val fieldName = volatileField.name.asString()
                val fuField = context.irFactory.buildField {
                    name = Name.identifier(mangleFieldUpdaterName(fieldName))
                    type = fieldUpdaterClass.defaultType
                    isFinal = true
                    isStatic = true
                    visibility = volatileField.visibility
                }.apply {
                    initializer = IrExpressionBodyImpl(
                        atomicSymbols.createBuilder(symbol).run {
                            newUpdater(fieldUpdaterClass, parentClass, irBuiltIns.anyNType, fieldName)
                        }
                    )
                    parent = parentClass
                }
                return context.addProperty(fuField, parentClass, true)
            } ?: error("Synthetic volatile property ${atomicProperty.render()} expected non null backingField")
        }

        private fun buildJucaArrayField(atomicArrayProperty: IrProperty, parentClass: IrClass) =
            atomicArrayProperty.backingField?.let { atomicArray ->
                getPropertyInitializer(atomicArray, parentClass)?.let {
                    val initializer = it as IrFunctionAccessExpression
                    val atomicArrayClass = atomicSymbols.getAtomicArrayClassByAtomicfuArrayName(atomicArray.type)
                    context.irFactory.buildField {
                        name = atomicArray.name
                        type = atomicArrayClass.defaultType
                        visibility = atomicArray.visibility
                    }.apply {
                        this.initializer = IrExpressionBodyImpl(
                            atomicSymbols.createBuilder(symbol).run {
                                val size = initializer.getValueArgument(0)!!.deepCopyWithSymbols()
                                newJucaAtomicArray(atomicArrayClass, size, initializer.dispatchReceiver)
                            }
                        )
                        annotations = atomicArray.annotations
                        parent = parentClass
                    }

                } ?: error("Atomic array ${atomicArrayProperty.render()} should be initialized either directly or in the init{} block")
            } ?: error("Atomic property does not have backingField") // todo property may not have initializer

        private fun buildVolatileWrapperClass(atomicProperty: IrProperty, parent: IrFile): IrClass {
            val wrapperClassName = getVolatileWrapperClassName(atomicProperty)
            val volatileWrapperClass = parent.declarations.singleOrNull { it is IrClass && it.name.asString() == wrapperClassName }
                ?: atomicSymbols.buildClassWithPrimaryConstructor(wrapperClassName, parent)
            return volatileWrapperClass as IrClass
        }

        private fun getPropertyInitializer(field: IrField, parentClass: IrClass): IrExpression? {
            field.initializer?.expression?.let { return it }
            parentClass.declarations.forEach { declaration ->
                if (declaration is IrAnonymousInitializer) {
                    declaration.body.statements.singleOrNull {
                        it is IrSetField && it.symbol == field.symbol
                    }?.let {
                        declaration.body.statements.remove(it) //todo is it ok&
                        return (it as IrSetField).value
                    }
                }
            }
            return null
        }

        private fun IrProperty.isWrapped(): Boolean =
            parent is IrClass && (parent as IrClass).name.asString().endsWith(VOLATILE_WRAPPER_SUFFIX)

        private fun isAtomic(property: IrDeclaration): Boolean {
            if (property !is IrProperty) return false
            property.backingField?.let { backingField ->
                return backingField.type.isAtomicValueType()
            }
            return false
        }

        private fun isAtomicArray(property: IrDeclaration): Boolean {
            if (property !is IrProperty) return false
            property.backingField?.let {
                return it.type.isAtomicArrayType()
            }
            return false
        }

        private fun isTrace(property: IrDeclaration): Boolean {
            if (property !is IrProperty) return false
            property.backingField?.let {
                return it.type.isTraceBaseType()
            }
            return false
        }

        private fun mangleFieldUpdaterName(fieldName: String) = "$fieldName\$FU"
    }

    private inner class AtomicExtensionTransformer : IrElementTransformerVoid() {
        override fun visitFile(declaration: IrFile): IrFile {
            declaration.addAllTransformedAtomicExtensions()
            return super.visitFile(declaration)
        }

        override fun visitClass(declaration: IrClass): IrStatement {
            declaration.addAllTransformedAtomicExtensions()
            return super.visitClass(declaration)
        }

        private fun IrDeclarationContainer.addAllTransformedAtomicExtensions() {
            declarations.filter { it is IrFunction && it.isAtomicExtension() }.forEach { atomicExtension ->
                atomicExtension as IrFunction
                declarations.add(buildTransformedAtomicExtension(atomicExtension, this, false))
                declarations.add(buildTransformedAtomicExtension(atomicExtension, this, true))
                declarations.remove(atomicExtension)
            }
        }

        private fun buildTransformedAtomicExtension(
            declaration: IrFunction,
            parentClass: IrDeclarationParent,
            isArrayReceiver: Boolean
        ): IrFunction {
            val mangledName = mangleFunctionName(declaration.name.asString(), isArrayReceiver)
            val valueType = declaration.extensionReceiverParameter!!.type.atomicToValueType()
            return context.irFactory.buildFun {
                name = Name.identifier(mangledName)
                isInline = true
            }.apply {
                val newDeclaration = this
                extensionReceiverParameter = null
                dispatchReceiverParameter = declaration.dispatchReceiverParameter?.deepCopyWithSymbols(this)
                if (isArrayReceiver) {
                    addValueParameter(DISPATCH_RECEIVER, irBuiltIns.anyNType)
                    addValueParameter(ATOMIC_HANDLER, atomicSymbols.getAtomicArrayType(valueType).defaultType)
                    addValueParameter(INDEX, irBuiltIns.intType)
                } else {
                    addValueParameter(DISPATCH_RECEIVER, irBuiltIns.anyNType)
                    addValueParameter(ATOMIC_HANDLER, atomicSymbols.getFieldUpdaterType(valueType))
                }
                declaration.valueParameters.forEach { addValueParameter(it.name, it.type).apply {
                    parent = newDeclaration // todo remove this
                } }
                body = declaration.body?.deepCopyWithSymbols(this)
                body?.transform(
                    object : IrElementTransformerVoid() {
                        override fun visitReturn(expression: IrReturn): IrExpression = super.visitReturn(
                            if (expression.returnTargetSymbol == declaration.symbol)
                                IrReturnImpl(
                                    expression.startOffset,
                                    expression.endOffset,
                                    expression.type,
                                    newDeclaration.symbol,
                                    expression.value
                                )
                            else
                                expression
                        )
                    }, null
                )
                returnType = declaration.returnType
                parent = parentClass
            }
        }
    }

    private data class AtomicFieldInfo(val dispatchReceiver: IrExpression?, val atomicHandler: IrExpression)

    private inner class AtomicFunctionsTransformer : IrElementTransformer<IrFunction?> {
        override fun visitFunction(declaration: IrFunction, data: IrFunction?): IrStatement {
            return super.visitFunction(declaration, declaration)
        }

        override fun visitCall(expression: IrCall, data: IrFunction?): IrElement {
            (expression.extensionReceiver ?: expression.dispatchReceiver)?.transform(this, data)?.let {
                atomicSymbols.createBuilder(expression.symbol).run {
                    val receiver = if (it is IrTypeOperatorCallImpl) it.argument else it
                    if (receiver.type.isAtomicValueType()) {
                        val valueType =
                            if (it is IrTypeOperatorCallImpl)
                                (it.type as IrSimpleType).arguments[0] as IrSimpleType
                            else receiver.type.atomicToValueType()
                        getAtomicFieldInfo(receiver, data)?.let { (parentClass, atomicHandler) ->
                            val isArrayReceiver = atomicSymbols.isAtomicArrayHandlerType(atomicHandler.type)
                            if (expression.symbol.isKotlinxAtomicfuPackage()) {
                                // Delegate invocations of atomic functions on atomic receivers
                                // to the corresponding functions of j.u.c.a.Atomic*FieldUpdater for atomics
                                // and to the j.u.c.a.Atomic*Array for atomic arrays

                                // In case of the atomic field receiver, pass field accessors:
                                // a.incrementAndGet() -> a$FU.incrementAndGet(a.dispatchReceiver)

                                // In case of the atomic `this` receiver, pass the dispatchReceiver and atomicHandler parameters
                                // from the transformed parent atomic extension declaration (data):
                                // Note: inline atomic extension signatures are already transformed with the [AtomicExtensionTransformer]
                                // inline fun foo(atomicfu$dispatchReceiver: Any?, atomicfu$handler: AtomicIntegerFieldUpdater) { incrementAndGet() } ->
                                // inline fun foo(atomicfu$dispatchReceiver: Any?, atomicfu$handler: AtomicIntegerFieldUpdater) { atomicfu$handler.incrementAndGet(atomicfu$dispatchReceiver) }
                                val functionName = expression.symbol.owner.name.asString()
                                if (functionName in ATOMICFU_INLINE_FUNCTIONS) {
                                    require(data != null) { "Function containing loop invocation ${expression.render()} is null" }
                                    val loopFunc = data.parentAsClass.getOrBuildInlineLoopFunction(
                                        functionName = functionName,
                                        valueType = if (valueType.isBoolean()) irBuiltIns.intType else valueType,
                                        atomicHandlerClassSymbol = atomicHandler.type.classOrNull!!,
                                        isArrayReceiver = isArrayReceiver
                                    )
                                    val action = (expression.getValueArgument(0) as IrFunctionExpression).apply {
                                        function.body?.transform(this@AtomicFunctionsTransformer, data)
                                        if (function.valueParameters[0].type.isBoolean()) {
                                            function.valueParameters[0].type = irBuiltIns.intType
                                            function.returnType = irBuiltIns.intType
                                        }
                                    }
                                    val loopCall = irCallWithArgs(
                                        symbol = loopFunc.symbol,
                                        dispatchReceiver = data.dispatchReceiverParameter?.capture(),
                                        valueArguments = if (isArrayReceiver) {
                                            val index = receiver.getArrayElementIndex(data)
                                            listOf(atomicHandler, index, action)
                                        } else {
                                            listOf(atomicHandler, action)
                                        }
                                    )
                                    return super.visitCall(loopCall, data)
                                }
                                val irCall = if (isArrayReceiver) {
                                    // delegate atomic function call to j.u.c.a.Atomic*Array
                                    callAtomicArray(
                                        arrayClassSymbol = atomicHandler.type.classOrNull!!,
                                        functionName = functionName,
                                        dispatchReceiver = atomicHandler,
                                        index = receiver.getArrayElementIndex(data),
                                        valueArguments = expression.getValueArguments(),
                                        isBooleanReceiver = valueType.isBoolean()
                                    )
                                } else {
                                    // delegate atomic function call to j.u.c.a.Atomic*FieldUpdater
                                    callFieldUpdater(
                                        fieldUpdaterSymbol = atomicSymbols.getFieldUpdaterClass(valueType),
                                        functionName = functionName,
                                        dispatchReceiver = atomicHandler,
                                        obj = parentClass,
                                        valueArguments = expression.getValueArguments(),
                                        castType = if (it is IrTypeOperatorCall) valueType else null,
                                        isBooleanReceiver = valueType.isBoolean()
                                    )
                                }
                                return super.visitExpression(irCall, data)
                            }
                            if (expression.symbol.owner.isInline && expression.extensionReceiver != null) {
                                // Transform invocation of the atomic extension on the atomic receiver,
                                // passing dispatchReceiver and updater of the field as parameters.

                                // In case of the atomic field receiver, pass dispatchReceiver and field updater:
                                // a.foo(arg) -> foo(arg, a.dispatchReceiver, a$FU)

                                // In case of the atomic `this` receiver, pass the corresponding dispatchReceiver and field updater parameters
                                // from the parent transformed atomic extension declaration:
                                // Note: inline atomic extension signatures are already transformed with the [AtomicExtensionTransformer]
                                // inline fun bar(atomicfu$dispatchReceiver: Any?, atomicfu$handler: AtomicIntegerFieldUpdater) { ... }
                                // inline fun foo(atomicfu$dispatchReceiver: Any?, atomicfu$handler: AtomicIntegerFieldUpdater) { this.bar() } ->
                                // inline fun foo(atomicfu$dispatchReceiver: Any?, atomicfu$handler: AtomicIntegerFieldUpdater) { bar(atomicfu$dispatchReceiver, atomicfu$handler) }
                                val declaration = expression.symbol.owner
                                val parent = declaration.parent as IrDeclarationContainer
                                val transformedAtomicExtension = parent.getTransformedAtomicExtension(declaration, isArrayReceiver)
                                require(data != null) { "Function containing invocation of the extension function ${expression.render()} is null" }
                                val irCall = callAtomicExtension(
                                    symbol = transformedAtomicExtension.symbol,
                                    dispatchReceiver = expression.dispatchReceiver, //todo check
                                    syntheticValueArguments = if (isArrayReceiver) listOf(parentClass, atomicHandler, receiver.getArrayElementIndex(data))
                                        else listOf(parentClass, atomicHandler),
                                    valueArguments = expression.getValueArguments()
                                )
                                return super.visitCall(irCall, data)
                            }
                        } ?: return expression
                    }
                }
            }
            return super.visitCall(expression, data)
        }

        override fun visitGetValue(expression: IrGetValue, data: IrFunction?): IrExpression {
            // For transformed atomic extension functions:
            // replace all usages of old value parameters with the new parameters of the transformed declaration
            // inline fun foo(arg', atomicfu$getter: () -> T, atomicfu$setter: (T) -> Unit) { bar(arg) } -> { bar(arg') }
            if (expression.symbol is IrValueParameterSymbol) {
                val valueParameter = expression.symbol.owner as IrValueParameter
                val parent = valueParameter.parent
                if (data != null && data.isTransformedAtomicExtension() &&
                    parent is IrFunctionImpl && !parent.isTransformedAtomicExtension() &&
                    parent.origin != IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA) {
                    val index = valueParameter.index
                    if (index < 0 && !valueParameter.type.isAtomicValueType()) return data.dispatchReceiverParameter!!.capture() // todo what if null
                    if (index >= 0) { // index == -1 for `this` parameter
                        val shift = if (data.name.asString().endsWith(ATOMIC_ARRAY_RECEIVER_SUFFIX)) 3 else 2
                        val transformedValueParameter = data.valueParameters[index + shift]
                        return buildGetValue(
                            expression.startOffset,
                            expression.endOffset,
                            transformedValueParameter.symbol
                        )
                    }
                }
            }
            return super.visitGetValue(expression, data)
        }

        override fun visitBlockBody(body: IrBlockBody, data: IrFunction?): IrBody {
            // Erase messages added by the Trace object from the function body:
            // val trace = Trace(size)
            // Messages may be added via trace invocation:
            // trace { "Doing something" }
            // or via multi-append of arguments:
            // trace.append(index, "CAS", value)
            body.statements.removeIf {
                it.isTraceCall()
            }
            return super.visitBlockBody(body, data)
        }

        override fun visitContainerExpression(expression: IrContainerExpression, data: IrFunction?): IrExpression {
            // Erase messages added by the Trace object from blocks.
            expression.statements.removeIf {
                it.isTraceCall()
            }
            return super.visitContainerExpression(expression, data)
        }

        private fun AtomicfuIrBuilder.getAtomicFieldInfo(
            receiver: IrExpression,
            parentFunction: IrFunction?
        ): AtomicFieldInfo? {
            when {
                receiver is IrCall -> {
                    // skip transformation of atomic extension function body
                    if (parentFunction != null && parentFunction.isAtomicExtension() && !parentFunction.isTransformedAtomicExtension()) return null
                    val isArray = receiver.isArrayElementGetter()
                    val getAtomicProperty = if (isArray) receiver.dispatchReceiver as IrCall else receiver
                    val atomicProperty = getAtomicProperty.getCorrespondingProperty()
                    val dispatchReceiver = getAtomicProperty.dispatchReceiver // parentFunction.dispatchReceiverParameter?.capture()
                        ?: run { // the field is static -> refVolatile wrapper
                            val refVolatileClassInstance = getStaticVolatileWrapperInstance(atomicProperty)
                            getProperty(refVolatileClassInstance, null)
                        }
                    val atomicHandler = propertyToAtomicHandler[atomicProperty]
                        ?: error("No atomic handler found for the atomic property ${atomicProperty.render()}")
                    return AtomicFieldInfo(
                        dispatchReceiver = dispatchReceiver,
                        atomicHandler = getProperty(atomicHandler, if (isArray) dispatchReceiver else null)
                    )
                }
                receiver.isThisReceiver() -> {
                    return if (parentFunction != null && parentFunction.isTransformedAtomicExtension()) {
                        val params = parentFunction.valueParameters.take(2).map { it.capture() }
                        AtomicFieldInfo(params[0], params[1])
                    } else null
                }
                else -> error("Unsupported type of atomic receiver expression: ${receiver.render()}")
            }
        }

        private fun IrExpression.getArrayElementIndex(parentFunction: IrFunction?): IrExpression =
            when {
                this is IrCall -> getValueArgument(0)!!
                this.isThisReceiver() -> {
                    require(parentFunction != null)
                    parentFunction.valueParameters[2].capture()
                }
                else -> error("Unsupported type of atomic receiver expression: ${this.render()}")
            }

        private fun IrExpression.isThisReceiver() =
            this is IrGetValue && symbol.owner.name.asString() == "<this>"

        private fun IrFunction.isTransformedAtomicExtension(): Boolean {
            val isArrayReceiver = name.asString().endsWith(ATOMIC_ARRAY_RECEIVER_SUFFIX)
            return if (isArrayReceiver) checkSyntheticArrayElementExtensionParameter() else checkSyntheticAtomicExtensionParameters()
        }

        private fun IrFunction.checkSyntheticArrayElementExtensionParameter(): Boolean {
            if (valueParameters.size < 3) return false
            return valueParameters[0].name.asString() == DISPATCH_RECEIVER && valueParameters[0].type == irBuiltIns.anyNType &&
                    valueParameters[1].name.asString() == ATOMIC_HANDLER && atomicSymbols.isAtomicArrayHandlerType(valueParameters[1].type) &&
                    valueParameters[2].name.asString() == INDEX && valueParameters[2].type == irBuiltIns.intType
        }

        private fun IrFunction.checkSyntheticAtomicExtensionParameters(): Boolean {
            if (valueParameters.size < 2) return false
            return valueParameters[0].name.asString() == DISPATCH_RECEIVER && valueParameters[0].type == irBuiltIns.anyNType &&
                    valueParameters[1].name.asString() == ATOMIC_HANDLER && atomicSymbols.isAtomicFieldUpdaterType(valueParameters[1].type)
        }

        private fun IrClass.getOrBuildInlineLoopFunction(
            functionName: String,
            valueType: IrType,
            atomicHandlerClassSymbol: IrClassSymbol, // todo we can get array class from the valueType
            isArrayReceiver: Boolean
        ): IrSimpleFunction {
            val parentClass = this
            val mangledName = mangleFunctionName(functionName, isArrayReceiver)
            val updaterType = if (isArrayReceiver) atomicHandlerClassSymbol.defaultType else atomicSymbols.getFieldUpdaterType(valueType)
            findDeclaration<IrSimpleFunction> {
                it.name.asString() == mangledName && it.valueParameters[0].type == updaterType
            }?.let { return it }
            return addFunction {
                name = Name.identifier(mangledName)
                isInline = true
            }.apply {
                dispatchReceiverParameter = parentClass.thisReceiver!!.deepCopyWithSymbols(this)
                if (functionName == LOOP) {
                    if (isArrayReceiver) generateAtomicfuArrayLoop(valueType) else generateAtomicfuLoop(valueType)
                } else {
                    if (isArrayReceiver) generateAtomicfuArrayUpdate(functionName, valueType) else generateAtomicfuUpdate(functionName, valueType)
                }
            }
        }

        private fun IrDeclarationContainer.getTransformedAtomicExtension(
            declaration: IrSimpleFunction,
            isArrayReceiver: Boolean
        ): IrSimpleFunction = findDeclaration {
                it.name.asString() == mangleFunctionName(declaration.name.asString(), isArrayReceiver) &&
                        it.isTransformedAtomicExtension()
            } ?: error("Could not find corresponding transformed declaration for the atomic extension ${declaration.render()}")

        private fun IrSimpleFunction.generateAtomicfuLoop(valueType: IrType) {
            addValueParameter(ATOMIC_HANDLER, atomicSymbols.getFieldUpdaterType(valueType))
            addValueParameter(ACTION, atomicSymbols.function1Type(valueType, irBuiltIns.unitType))
            body = atomicSymbols.createBuilder(symbol).run {
                atomicfuLoopBody(valueType, valueParameters, dispatchReceiverParameter!!.capture())
            }
            returnType = irBuiltIns.unitType
        }

        private fun IrSimpleFunction.generateAtomicfuArrayLoop(valueType: IrType) {
            val atomicfuArrayClass = atomicSymbols.getAtomicArrayType(valueType)
            addValueParameter(ATOMIC_HANDLER, atomicfuArrayClass.defaultType)
            addValueParameter(INDEX, irBuiltIns.intType)
            addValueParameter(ACTION, atomicSymbols.function1Type(valueType, irBuiltIns.unitType))
            body = atomicSymbols.createBuilder(symbol).run {
                atomicfuArrayLoopBody(atomicfuArrayClass, valueParameters)
            }
            returnType = irBuiltIns.unitType
        }

        private fun IrSimpleFunction.generateAtomicfuUpdate(functionName: String, valueType: IrType) {
            addValueParameter(ATOMIC_HANDLER, atomicSymbols.getFieldUpdaterType(valueType))
            addValueParameter(ACTION, atomicSymbols.function1Type(valueType, valueType))
            body = atomicSymbols.createBuilder(symbol).run {
                atomicfuUpdateBody(functionName, valueParameters, valueType, dispatchReceiverParameter!!.capture())
            }
            returnType = if (functionName == UPDATE) irBuiltIns.unitType else valueType
        }

        private fun IrSimpleFunction.generateAtomicfuArrayUpdate(functionName: String, valueType: IrType) {
            val atomicfuArrayClass = atomicSymbols.getAtomicArrayType(valueType)
            addValueParameter(ATOMIC_HANDLER, atomicfuArrayClass.defaultType)
            addValueParameter(INDEX, irBuiltIns.intType)
            addValueParameter(ACTION, atomicSymbols.function1Type(valueType, valueType))
            body = atomicSymbols.createBuilder(symbol).run {
                atomicfuArrayUpdateBody(functionName, atomicfuArrayClass, valueParameters)
            }
            returnType = if (functionName == UPDATE) irBuiltIns.unitType else valueType
        }
    }

    private fun getStaticVolatileWrapperInstance(atomicProperty: IrProperty): IrProperty {
        val refVolatileStaticPropertyName = getVolatileWrapperClassName(atomicProperty).replaceFirstChar { it.lowercaseChar() }
        return atomicProperty.fileParent.declarations.singleOrNull {
            it is IrProperty && it.name.asString() == refVolatileStaticPropertyName
        } as? IrProperty
            ?: error("Static instance of $refVolatileStaticPropertyName is missing in the file ${atomicProperty.fileParent.render()}")
    }

    private fun IrType.isAtomicValueType() =
        classFqName?.let {
            it.parent().asString() == AFU_PKG && it.shortName().asString() in ATOMIC_VALUE_TYPES
        } ?: false

    private fun IrType.isAtomicArrayType() =
        classFqName?.let {
            it.parent().asString() == AFU_PKG && it.shortName().asString() in ATOMIC_ARRAY_TYPES
        } ?: false

    private fun IrType.isTraceBaseType() =
        classFqName?.let {
            it.parent().asString() == AFU_PKG && it.shortName().asString() == TRACE_BASE_TYPE
        } ?: false

    private fun IrCall.isArrayElementGetter(): Boolean =
        dispatchReceiver?.let {
            it.type.isAtomicArrayType() && symbol.owner.name.asString() == "get"
        } ?: false

    private fun IrType.atomicToValueType(): IrType =
        classFqName?.let {
            AFU_VALUE_TYPES[it.shortName().asString()]
        } ?: error("No corresponding value type was found for this atomic type: ${this.render()}")


    private fun IrType.getSignature(): IdSignature.CommonSignature? = classOrNull?.let { it.signature?.asPublic() }

    private fun IrSimpleFunctionSymbol.isKotlinxAtomicfuPackage(): Boolean =
        owner.parentClassOrNull?.classId?.let {
            it.packageFqName.asString() == AFU_PKG
        } ?: false

    private fun IrCall.isAtomicFactory(): Boolean =
        symbol.isKotlinxAtomicfuPackage() && symbol.owner.name.asString() == ATOMIC_VALUE_FACTORY &&
                type.isAtomicValueType()

    private fun IrFunction.isAtomicExtension(): Boolean =
        extensionReceiverParameter?.let { it.type.isAtomicValueType() && this.isInline } ?: false

    private fun IrCall.isAtomicFieldGetter(): Boolean =
        type.isAtomicValueType() && symbol.owner.name.asString().startsWith("<get-")

    private fun IrCall.isGetValue() = symbol.isKotlinxAtomicfuPackage() && symbol.owner.name.asString() == GET_VALUE
    private fun IrCall.isSetValue() = symbol.isKotlinxAtomicfuPackage() && symbol.owner.name.asString() == SET_VALUE

    private fun IrStatement.isTraceCall() = this is IrCall && (isTraceInvoke() || isTraceAppend())

    private fun IrCall.isTraceInvoke(): Boolean =
        symbol.isKotlinxAtomicfuPackage() &&
                symbol.owner.name.asString() == INVOKE &&
                symbol.owner.dispatchReceiverParameter?.type?.isTraceBaseType() == true

    private fun IrCall.isTraceAppend(): Boolean =
        symbol.isKotlinxAtomicfuPackage() &&
                symbol.owner.name.asString() == APPEND &&
                symbol.owner.dispatchReceiverParameter?.type?.isTraceBaseType() == true

    // todo do not need to use fileParent name anymore -> use just parent
    private fun getVolatileWrapperClassName(property: IrProperty) =
        property.name.asString().capitalizeAsciiOnly() + '$' + property.fileParent.name.substringBefore('.') + VOLATILE_WRAPPER_SUFFIX
    private fun mangleFunctionName(name: String, isArrayReceiver: Boolean) =
        "$name\$$ATOMICFU" + if (isArrayReceiver) ATOMIC_ARRAY_RECEIVER_SUFFIX else ""
}