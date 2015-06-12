/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve.scopes

import com.google.common.collect.*
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.checker.JetTypeChecker
import org.jetbrains.kotlin.utils.Printer

import java.util.*
import com.intellij.util.SmartList
import org.jetbrains.kotlin.util.collectionUtils.concatInOrder

// Reads from:
// 1. Maps
// 2. Worker

// Writes to: maps

public class WritableScopeImpl @jvmOverloads constructor(
        outerScope: JetScope,
        private val ownerDeclarationDescriptor: DeclarationDescriptor,
        protected val redeclarationHandler: RedeclarationHandler,
        private val debugName: String,
        private val labeledDeclaration: DeclarationDescriptor? = null
) : AbstractScopeAdapter(), WritableScope {

    private val explicitlyAddedDescriptors = SmartList<DeclarationDescriptor>()

    private var functionGroups: MutableMap<Name, IntList>? = null

    private var variableOrClassDescriptors: MutableMap<Name, IntList>? = null

    private var implicitReceiver: ReceiverParameterDescriptor? = null

    private var implicitReceiverHierarchy: List<ReceiverParameterDescriptor>? = null

    override fun getContainingDeclaration(): DeclarationDescriptor = ownerDeclarationDescriptor

    private var lastSnapshot: Snapshot? = null

    private var lockLevel: WritableScope.LockLevel = WritableScope.LockLevel.WRITING

    override val workerScope: JetScope = if (outerScope is WritableScope) outerScope.takeSnapshot() else outerScope

    override fun changeLockLevel(lockLevel: WritableScope.LockLevel): WritableScope {
        if (lockLevel.ordinal() < this.lockLevel.ordinal()) {
            throw IllegalStateException("cannot lower lock level from " + this.lockLevel + " to " + lockLevel + " at " + toString())
        }
        this.lockLevel = lockLevel
        return this
    }

    protected fun checkMayRead() {
        if (lockLevel != WritableScope.LockLevel.READING && lockLevel != WritableScope.LockLevel.BOTH) {
            throw IllegalStateException("cannot read with lock level " + lockLevel + " at " + toString())
        }
    }

    protected fun checkMayWrite() {
        if (lockLevel != WritableScope.LockLevel.WRITING && lockLevel != WritableScope.LockLevel.BOTH) {
            throw IllegalStateException("cannot write with lock level " + lockLevel + " at " + toString())
        }
    }

    override fun takeSnapshot(): JetScope {
        checkMayRead()
        if (lastSnapshot == null || lastSnapshot!!.descriptorLimit != explicitlyAddedDescriptors.size()) {
            lastSnapshot = Snapshot(explicitlyAddedDescriptors.size())
        }
        return lastSnapshot!!
    }

    override fun getDescriptors(kindFilter: DescriptorKindFilter,
                                nameFilter: (Name) -> Boolean): Collection<DeclarationDescriptor> {
        checkMayRead()
        changeLockLevel(WritableScope.LockLevel.READING)

        val result = ArrayList<DeclarationDescriptor>()
        result.addAll(explicitlyAddedDescriptors)
        result.addAll(workerScope.getDescriptors(kindFilter, nameFilter))
        return result
    }

    override fun getDeclarationsByLabel(labelName: Name): Collection<DeclarationDescriptor> {
        checkMayRead()

        val superResult = super<AbstractScopeAdapter>.getDeclarationsByLabel(labelName)
        return if (labeledDeclaration != null && labeledDeclaration.getName() == labelName)
            listOf(labeledDeclaration) + superResult
        else
            superResult
    }

    private fun addVariableOrClassDescriptor(descriptor: DeclarationDescriptor) {
        checkMayWrite()

        val name = descriptor.getName()

        val originalDescriptor = variableOrClassDescriptorByName(name)
        if (originalDescriptor != null) {
            redeclarationHandler.handleRedeclaration(originalDescriptor, descriptor)
        }

        val descriptorIndex = addDescriptor(descriptor)

        if (variableOrClassDescriptors == null) {
            variableOrClassDescriptors = HashMap()
        }
        //TODO: could not use += because of KT-8050
        variableOrClassDescriptors!![name] = variableOrClassDescriptors!![name] + descriptorIndex

    }

    override fun addVariableDescriptor(variableDescriptor: VariableDescriptor) {
        addVariableOrClassDescriptor(variableDescriptor)
    }

    override fun getLocalVariable(name: Name): VariableDescriptor? {
        checkMayRead()

        val descriptor = variableOrClassDescriptorByName(name)
        if (descriptor is VariableDescriptor) {
            return descriptor
        }

        return workerScope.getLocalVariable(name)
    }

    override fun addFunctionDescriptor(functionDescriptor: FunctionDescriptor) {
        checkMayWrite()

        val descriptorIndex = addDescriptor(functionDescriptor)

        if (functionGroups == null) {
            functionGroups = HashMap(1)
        }
        val name = functionDescriptor.getName()
        //TODO: could not use += because of KT-8050
        functionGroups!![name] = functionGroups!![name] + descriptorIndex
    }

    override fun getFunctions(name: Name): Collection<FunctionDescriptor> {
        checkMayRead()

        return concatInOrder(functionsByName(name), workerScope.getFunctions(name))
    }

    override fun addClassifierDescriptor(classifierDescriptor: ClassifierDescriptor) {
        addVariableOrClassDescriptor(classifierDescriptor)
    }

    override fun getClassifier(name: Name): ClassifierDescriptor? {
        checkMayRead()

        return variableOrClassDescriptorByName(name) as? ClassifierDescriptor
               ?: workerScope.getClassifier(name)
    }

    override fun setImplicitReceiver(implicitReceiver: ReceiverParameterDescriptor) {
        checkMayWrite()

        if (this.implicitReceiver != null) {
            throw UnsupportedOperationException("Receiver redeclared")
        }
        this.implicitReceiver = implicitReceiver
    }

    override fun getImplicitReceiversHierarchy(): List<ReceiverParameterDescriptor> {
        checkMayRead()

        if (implicitReceiverHierarchy == null) {
            implicitReceiverHierarchy = computeImplicitReceiversHierarchy()
        }
        return implicitReceiverHierarchy!!
    }

    private fun computeImplicitReceiversHierarchy(): List<ReceiverParameterDescriptor> {
        return if (implicitReceiver != null)
            listOf(implicitReceiver!!) + super<AbstractScopeAdapter>.getImplicitReceiversHierarchy()
        else
            super<AbstractScopeAdapter>.getImplicitReceiversHierarchy()
    }

    override fun getOwnDeclaredDescriptors(): Collection<DeclarationDescriptor> = explicitlyAddedDescriptors

    private fun variableOrClassDescriptorByName(name: Name, descriptorLimit: Int = explicitlyAddedDescriptors.size()): DeclarationDescriptor? {
        if (descriptorLimit == 0) return null

        var list = variableOrClassDescriptors?.get(name)
        while (list != null) {
            val descriptorIndex = list.head
            if (descriptorIndex < descriptorLimit) {
                return descriptorIndex.descriptorByIndex()
            }
            list = list.tail
        }
        return null
    }

    private fun functionsByName(name: Name, descriptorLimit: Int = explicitlyAddedDescriptors.size()): List<FunctionDescriptor>? {
        if (descriptorLimit == 0) return null

        var list = functionGroups?.get(name)
        while (list != null) {
            if (list.head < descriptorLimit) {
                return list.toDescriptors<FunctionDescriptor>()
            }
            list = list.tail
        }
        return null
    }

    private fun addDescriptor(descriptor: DeclarationDescriptor): Int {
        explicitlyAddedDescriptors.add(descriptor)
        return explicitlyAddedDescriptors.size() - 1
    }

    private fun Int.descriptorByIndex() = explicitlyAddedDescriptors[this]

    override fun toString(): String {
        return javaClass.getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(this)) + " " + debugName + " for " + getContainingDeclaration()
    }

    override fun printScopeStructure(p: Printer) {
        p.println(javaClass.getSimpleName(), ": ", debugName, " for ", getContainingDeclaration(), " {")
        p.pushIndent()

        p.println("lockLevel = ", lockLevel)

        p.print("worker = ")
        workerScope.printScopeStructure(p.withholdIndentOnce())

        p.popIndent()
        p.println("}")
    }

    private class IntList(val head: Int, val tail: IntList?)

    private fun IntList?.plus(value: Int) = IntList(value, this)

    private fun <TDescriptor: DeclarationDescriptor> IntList.toDescriptors(): List<TDescriptor> {
        val result = ArrayList<TDescriptor>(1)
        var rest: IntList? = this
        do {
            result.add(rest!!.head.descriptorByIndex() as TDescriptor)
            rest = rest.tail
        } while (rest != null)
        return result
    }

    private inner class Snapshot(val descriptorLimit: Int) : JetScope by this@WritableScopeImpl {
        override fun getDescriptors(kindFilter: DescriptorKindFilter,
                                    nameFilter: (Name) -> Boolean): Collection<DeclarationDescriptor> {
            checkMayRead()
            changeLockLevel(WritableScope.LockLevel.READING)

            val workerResult = workerScope.getDescriptors(kindFilter, nameFilter)

            if (descriptorLimit == 0) return workerResult

            val result = ArrayList<DeclarationDescriptor>(workerResult.size() + descriptorLimit)
            for (i in 0..descriptorLimit-1) {
                result.add(explicitlyAddedDescriptors[i])
            }
            result.addAll(workerResult)
            return result
        }

        override fun getLocalVariable(name: Name): VariableDescriptor? {
            checkMayRead()

            val descriptor = variableOrClassDescriptorByName(name, descriptorLimit)
            if (descriptor is VariableDescriptor) {
                return descriptor
            }

            return workerScope.getLocalVariable(name)
        }

        override fun getFunctions(name: Name): Collection<FunctionDescriptor> {
            checkMayRead()

            return concatInOrder(functionsByName(name, descriptorLimit), workerScope.getFunctions(name))
        }

        override fun getClassifier(name: Name): ClassifierDescriptor? {
            checkMayRead()

            return variableOrClassDescriptorByName(name, descriptorLimit) as? ClassifierDescriptor
                   ?: workerScope.getClassifier(name)
        }

        override fun getOwnDeclaredDescriptors(): Collection<DeclarationDescriptor> = explicitlyAddedDescriptors.truncated(descriptorLimit)

        private fun <T> List<T>.truncated(newSize: Int) = if (newSize == size()) this else subList(0, newSize)

        override fun printScopeStructure(p: Printer) {
            p.println(javaClass.getSimpleName(), " {")
            p.pushIndent()

            p.println("descriptorLimit = ", descriptorLimit)

            p.print("WritableScope = ")
            this@WritableScopeImpl.printScopeStructure(p.withholdIndentOnce())

            p.popIndent()
            p.println("}")
        }
    }
}
