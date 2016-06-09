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

package org.jetbrains.kotlin.load.java.structure.impl;

import com.intellij.psi.*;
import kotlin.collections.ArraysKt;
import kotlin.collections.CollectionsKt;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.asJava.KtJavaMirrorMarker;
import org.jetbrains.kotlin.descriptors.Visibility;
import org.jetbrains.kotlin.load.java.structure.*;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.name.SpecialNames;

import java.util.Collection;
import java.util.List;

import static org.jetbrains.kotlin.load.java.structure.impl.JavaElementCollectionFromPsiArrayUtil.*;

public class JavaClassImpl extends JavaClassifierImpl<PsiClass> implements JavaClass, JavaAnnotationOwnerImpl, JavaModifierListOwnerImpl {
    public JavaClassImpl(@NotNull PsiClass psiClass) {
        super(psiClass);
        assert !(psiClass instanceof PsiTypeParameter)
                : "PsiTypeParameter should be wrapped in JavaTypeParameter, not JavaClass: use JavaClassifier.create()";
    }

    @Override
    @NotNull
    public Collection<JavaClass> getInnerClasses() {
        return classes(getPsi().getInnerClasses());
    }

    @Override
    @Nullable
    public FqName getFqName() {
        String qualifiedName = getPsi().getQualifiedName();
        return qualifiedName == null ? null : new FqName(qualifiedName);
    }

    @NotNull
    @Override
    public Name getName() {
        return SpecialNames.safeIdentifier(getPsi().getName());
    }

    @Override
    public boolean isInterface() {
        return getPsi().isInterface();
    }

    @Override
    public boolean isAnnotationType() {
        return getPsi().isAnnotationType();
    }

    @Override
    public boolean isEnum() {
        return getPsi().isEnum();
    }

    @Override
    @Nullable
    public JavaClassImpl getOuterClass() {
        PsiClass outer = getPsi().getContainingClass();
        return outer == null ? null : new JavaClassImpl(outer);
    }

    @NotNull
    @Override
    public List<JavaTypeParameter> getTypeParameters() {
        return typeParameters(getPsi().getTypeParameters());
    }

    @Override
    @NotNull
    public Collection<JavaClassifierType> getSupertypes() {
        return classifierTypes(getPsi().getSuperTypes());
    }

    @Override
    @NotNull
    public Collection<JavaMethod> getMethods() {
        // We apply distinct here because PsiClass#getMethods() can return duplicate PSI methods, for example in Lombok (see KT-11778)
        return CollectionsKt.distinct(methods(ArraysKt.filter(getPsi().getMethods(), new Function1<PsiMethod, Boolean>() {
            @Override
            public Boolean invoke(PsiMethod method) {
                // Return type seems to be null for example for the 'clone' Groovy method generated by @AutoClone (see EA-73795)
                return !method.isConstructor() && method.getReturnType() != null;
            }
        })));
    }

    @Override
    @NotNull
    public Collection<JavaField> getFields() {
        // ex. Android plugin generates LightFields for resources started from '.' (.DS_Store file etc)
        return fields(ArraysKt.filter(getPsi().getFields(), new Function1<PsiField, Boolean>() {
            @Override
            public Boolean invoke(PsiField field) {
                String name = field.getName();
                return name != null && Name.isValidIdentifier(name);
            }
        }));
    }

    @Override
    @NotNull
    public Collection<JavaConstructor> getConstructors() {
        return constructors(ArraysKt.filter(getPsi().getConstructors(), new Function1<PsiMethod, Boolean>() {
            @Override
            public Boolean invoke(PsiMethod method) {
                // See for example org.jetbrains.plugins.scala.lang.psi.light.ScFunctionWrapper,
                // which is present in getConstructors(), but its isConstructor() returns false
                return method.isConstructor();
            }
        }));
    }

    @Override
    public boolean isAbstract() {
        return JavaElementUtil.isAbstract(this);
    }

    @Override
    public boolean isStatic() {
        return JavaElementUtil.isStatic(this);
    }

    @Override
    public boolean isFinal() {
        return JavaElementUtil.isFinal(this);
    }

    @NotNull
    @Override
    public Visibility getVisibility() {
        return JavaElementUtil.getVisibility(this);
    }

    @Override
    public boolean isKotlinLightClass() {
        return getPsi() instanceof KtJavaMirrorMarker;
    }

    @Nullable
    @Override
    public PsiAnnotationOwner getAnnotationOwnerPsi() {
        return getPsi().getModifierList();
    }
}
