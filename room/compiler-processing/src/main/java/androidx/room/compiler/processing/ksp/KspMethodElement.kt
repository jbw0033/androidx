/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.room.compiler.processing.ksp

import androidx.room.compiler.processing.XDeclaredType
import androidx.room.compiler.processing.XExecutableParameterElement
import androidx.room.compiler.processing.XMethodElement
import androidx.room.compiler.processing.XMethodType
import androidx.room.compiler.processing.XType
import androidx.room.compiler.processing.XTypeElement
import androidx.room.compiler.processing.ksp.synthetic.KspSyntheticContinuationParameterElement
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier

internal sealed class KspMethodElement(
    env: KspProcessingEnv,
    containing: KspTypeElement,
    declaration: KSFunctionDeclaration
) : KspExecutableElement(
    env = env,
    containing = containing,
    declaration = declaration
),
    XMethodElement {

    override val name: String by lazy {
        declaration.simpleName.asString()
    }

    override val executableType: XMethodType by lazy {
        KspMethodType.create(
            env = env,
            origin = this,
            containing = this.containing.type
        )
    }

    override fun isJavaDefault(): Boolean {
        return declaration.modifiers.contains(Modifier.JAVA_DEFAULT) || declaration.isJvmDefault()
    }

    override fun asMemberOf(other: XDeclaredType): XMethodType {
        check(other is KspDeclaredType)
        return KspMethodType.create(
            env = env,
            origin = this,
            containing = other
        )
    }

    override fun hasKotlinDefaultImpl(): Boolean {
        // see https://github.com/google/ksp/issues/32
        val parentDeclaration = declaration.parentDeclaration
        // if parent declaration is an interface and we are not marked as an abstract method,
        // we should have a default implementation
        return parentDeclaration is KSClassDeclaration &&
            parentDeclaration.classKind == ClassKind.INTERFACE &&
            !declaration.isAbstract
    }

    override fun overrides(other: XMethodElement, owner: XTypeElement): Boolean {
        return env.resolver.overrides(this, other)
    }

    override fun copyTo(newContainer: XTypeElement): KspMethodElement {
        check(newContainer is KspTypeElement)
        return create(
            env = env,
            containing = newContainer,
            declaration = declaration
        )
    }

    private class KspNormalMethodElement(
        env: KspProcessingEnv,
        containing: KspTypeElement,
        declaration: KSFunctionDeclaration
    ) : KspMethodElement(
        env, containing, declaration
    ) {
        override val returnType: XType by lazy {
            env.wrap(
                checkNotNull(declaration.returnType) {
                    "return type on a method declaration cannot be null"
                }
            )
        }
        override fun isSuspendFunction() = false
    }

    private class KspSuspendMethodElement(
        env: KspProcessingEnv,
        containing: KspTypeElement,
        declaration: KSFunctionDeclaration
    ) : KspMethodElement(
        env, containing, declaration
    ) {
        override fun isSuspendFunction() = true

        override val returnType: XType by lazy {
            env.wrap(env.resolver.builtIns.anyType.makeNullable())
        }

        override val parameters: List<XExecutableParameterElement>
            get() = super.parameters + KspSyntheticContinuationParameterElement(
                env = env,
                containing = this
            )
    }

    companion object {
        fun create(
            env: KspProcessingEnv,
            containing: KspTypeElement,
            declaration: KSFunctionDeclaration
        ): KspMethodElement {
            return if (declaration.modifiers.contains(Modifier.SUSPEND)) {
                KspSuspendMethodElement(env, containing, declaration)
            } else {
                KspNormalMethodElement(env, containing, declaration)
            }
        }
    }
}
