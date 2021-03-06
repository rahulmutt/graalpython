/*
 * Copyright (c) 2017, 2020, Oracle and/or its affiliates.
 * Copyright (c) 2013, Regents of the University of California
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.graal.python.builtins.objects.str;

import static com.oracle.graal.python.nodes.SpecialMethodNames.__ADD__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__CONTAINS__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__EQ__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__FORMAT__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__GETITEM__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__GETNEWARGS__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__GE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__GT__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__ITER__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__LE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__LT__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__MOD__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__MUL__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__NE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__REPR__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__RMUL__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__STR__;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.IndexError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.LookupError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.MemoryError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.TypeError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.UnicodeEncodeError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.ValueError;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.text.CaseMap;
import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.annotations.ArgumentClinic;
import com.oracle.graal.python.annotations.ArgumentClinic.ClinicConversion;
import com.oracle.graal.python.builtins.Builtin;
import com.oracle.graal.python.builtins.CoreFunctions;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.PythonBuiltins;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.PNotImplemented;
import com.oracle.graal.python.builtins.objects.bytes.BytesUtils;
import com.oracle.graal.python.builtins.objects.cext.PythonAbstractNativeObject;
import com.oracle.graal.python.builtins.objects.common.FormatNodeBase;
import com.oracle.graal.python.builtins.objects.common.HashingCollectionNodes;
import com.oracle.graal.python.builtins.objects.common.HashingStorage;
import com.oracle.graal.python.builtins.objects.common.HashingStorageLibrary;
import com.oracle.graal.python.builtins.objects.common.SequenceNodes.GetObjectArrayNode;
import com.oracle.graal.python.builtins.objects.common.SequenceNodesFactory.GetObjectArrayNodeGen;
import com.oracle.graal.python.builtins.objects.dict.PDict;
import com.oracle.graal.python.builtins.objects.function.PArguments;
import com.oracle.graal.python.builtins.objects.iterator.PStringIterator;
import com.oracle.graal.python.builtins.objects.list.ListBuiltins.ListReverseNode;
import com.oracle.graal.python.builtins.objects.list.PList;
import com.oracle.graal.python.builtins.objects.object.PythonObjectLibrary;
import com.oracle.graal.python.builtins.objects.range.RangeNodes.LenOfRangeNode;
import com.oracle.graal.python.builtins.objects.slice.PSlice;
import com.oracle.graal.python.builtins.objects.slice.PSlice.SliceInfo;
import com.oracle.graal.python.builtins.objects.str.StringBuiltinsClinicProviders.FormatNodeClinicProviderGen;
import com.oracle.graal.python.builtins.objects.str.StringBuiltinsClinicProviders.SplitNodeClinicProviderGen;
import com.oracle.graal.python.builtins.objects.str.StringNodes.CastToJavaStringCheckedNode;
import com.oracle.graal.python.builtins.objects.str.StringNodes.JoinInternalNode;
import com.oracle.graal.python.builtins.objects.str.StringNodes.SpliceNode;
import com.oracle.graal.python.builtins.objects.str.StringNodes.StringLenNode;
import com.oracle.graal.python.builtins.objects.str.StringNodes.StringMaterializeNode;
import com.oracle.graal.python.builtins.objects.str.StringUtils.StripKind;
import com.oracle.graal.python.builtins.objects.tuple.PTuple;
import com.oracle.graal.python.builtins.objects.tuple.TupleBuiltins;
import com.oracle.graal.python.builtins.objects.type.TypeNodes.IsSameTypeNode;
import com.oracle.graal.python.nodes.ErrorMessages;
import com.oracle.graal.python.nodes.PGuards;
import com.oracle.graal.python.nodes.PRaiseNode;
import com.oracle.graal.python.nodes.SpecialMethodNames;
import com.oracle.graal.python.nodes.builtins.ListNodes.AppendNode;
import com.oracle.graal.python.nodes.call.special.LookupAndCallBinaryNode;
import com.oracle.graal.python.nodes.classes.IsSubtypeNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinBaseNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonBinaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonBinaryClinicBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonQuaternaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonQuaternaryClinicBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonTernaryClinicBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonUnaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.clinic.ArgumentClinicProvider;
import com.oracle.graal.python.nodes.subscript.GetItemNode;
import com.oracle.graal.python.nodes.subscript.SliceLiteralNode.CastToSliceComponentNode;
import com.oracle.graal.python.nodes.subscript.SliceLiteralNode.CoerceToIntSlice;
import com.oracle.graal.python.nodes.subscript.SliceLiteralNode.ComputeIndices;
import com.oracle.graal.python.nodes.truffle.PythonArithmeticTypes;
import com.oracle.graal.python.nodes.util.CannotCastException;
import com.oracle.graal.python.nodes.util.CastToJavaIntExactNode;
import com.oracle.graal.python.nodes.util.CastToJavaStringNode;
import com.oracle.graal.python.nodes.util.CastToJavaStringNodeGen;
import com.oracle.graal.python.runtime.ExecutionContext.IndirectCallContext;
import com.oracle.graal.python.runtime.PythonContext;
import com.oracle.graal.python.runtime.PythonOptions;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.graal.python.runtime.formatting.InternalFormat;
import com.oracle.graal.python.runtime.formatting.InternalFormat.Spec;
import com.oracle.graal.python.runtime.formatting.StringFormatProcessor;
import com.oracle.graal.python.runtime.formatting.TextFormatter;
import com.oracle.graal.python.util.OverflowException;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.CachedLanguage;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.profiles.LoopConditionProfile;

@CoreFunctions(extendClasses = PythonBuiltinClassType.PString)
public final class StringBuiltins extends PythonBuiltins {

    @Override
    protected List<com.oracle.truffle.api.dsl.NodeFactory<? extends PythonBuiltinBaseNode>> getNodeFactories() {
        return StringBuiltinsFactory.getFactories();
    }

    @Builtin(name = __STR__, minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class StrNode extends PythonUnaryBuiltinNode {
        @Specialization
        static String doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castToJavaStringNode) {
            return castToJavaStringNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, __STR__, self);
        }
    }

    @Builtin(name = __FORMAT__, minNumOfPositionalArgs = 2, parameterNames = {"$self", "format_spec"})
    @ArgumentClinic(name = "format_spec", conversion = ClinicConversion.String)
    @GenerateNodeFactory
    @TypeSystemReference(PythonArithmeticTypes.class)
    abstract static class FormatNode extends FormatNodeBase {
        @Override
        protected ArgumentClinicProvider getArgumentClinic() {
            return FormatNodeClinicProviderGen.INSTANCE;
        }

        @Specialization(guards = "!formatString.isEmpty()")
        Object format(Object self, String formatString,
                        @Cached CastToJavaStringCheckedNode castToJavaStringNode) {
            // We cannot cast self via argument clinic, because we need to keep it as-is for the
            // empty format string case, which should call __str__, which may be overridden
            String str = castToJavaStringNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, __STR__, self);
            return formatString(getRaiseNode(), getAndValidateSpec(formatString), str);
        }

        @TruffleBoundary
        private static Object formatString(PRaiseNode raiseNode, Spec spec, String str) {
            TextFormatter formatter = new TextFormatter(raiseNode, spec.withDefaults(Spec.STRING));
            formatter.format(str);
            return formatter.pad().getResult();
        }

        private Spec getAndValidateSpec(String formatString) {
            Spec spec = InternalFormat.fromText(getRaiseNode(), formatString, __FORMAT__);
            if (Spec.specified(spec.type) && spec.type != 's') {
                throw raise(PythonBuiltinClassType.TypeError, ErrorMessages.UNKNOWN_FORMAT_CODE, spec.type, "str");
            }
            if (spec.alternate) {
                throw raise(PythonBuiltinClassType.ValueError, ErrorMessages.ALTERNATE_NOT_ALLOWED_WITH_STRING_FMT);
            }
            if (Spec.specified(spec.align) && spec.align == '=') {
                throw raise(PythonBuiltinClassType.ValueError, ErrorMessages.EQUALS_ALIGNMENT_FLAG_NOT_ALLOWED_FOR_STRING_FMT);
            }
            if (Spec.specified(spec.sign)) {
                throw raise(PythonBuiltinClassType.ValueError, ErrorMessages.SIGN_NOT_ALLOWED_FOR_STRING_FMT);
            }
            return spec;
        }
    }

    @Builtin(name = __REPR__, minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class ReprNode extends PythonUnaryBuiltinNode {

        @Specialization
        static String doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castToJavaStringNode) {
            return repr(castToJavaStringNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, __REPR__, self));
        }

        @TruffleBoundary
        static String repr(String self) {
            boolean hasSingleQuote = self.contains("'");
            boolean hasDoubleQuote = self.contains("\"");
            boolean useDoubleQuotes = hasSingleQuote && !hasDoubleQuote;

            StringBuilder str = new StringBuilder(self.length() + 2);
            byte[] buffer = new byte[12];
            str.append(useDoubleQuotes ? '"' : '\'');
            int offset = 0;
            while (offset < self.length()) {
                int codepoint = self.codePointAt(offset);
                switch (codepoint) {
                    case '"':
                        if (useDoubleQuotes) {
                            str.append("\\\"");
                        } else {
                            str.append('\"');
                        }
                        break;
                    case '\'':
                        if (useDoubleQuotes) {
                            str.append('\'');
                        } else {
                            str.append("\\'");
                        }
                        break;
                    case '\\':
                        str.append("\\\\");
                        break;
                    default:
                        if (StringUtils.isPrintable(codepoint)) {
                            str.appendCodePoint(codepoint);
                        } else {
                            int len = BytesUtils.unicodeEscape(codepoint, 0, buffer);
                            str.ensureCapacity(str.length() + len);
                            for (int i = 0; i < len; i++) {
                                str.append((char) buffer[i]);
                            }
                        }
                        break;
                }
                offset += Character.charCount(codepoint);
            }
            str.append(useDoubleQuotes ? '"' : '\'');
            return str.toString();
        }

    }

    @Builtin(name = __GETNEWARGS__, minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class GetNewargsNode extends PythonUnaryBuiltinNode {
        @Specialization
        PTuple doString(String self) {
            // CPython requires the string to be a copy for some reason
            return factory().createTuple(new Object[]{new String(self)});
        }

        @Specialization
        PTuple doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode cast) {
            return doString(cast.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, __GETNEWARGS__, self));
        }
    }

    abstract static class BinaryStringOpNode extends PythonBinaryBuiltinNode {
        @Specialization
        boolean eq(String self, String other) {
            return operator(self, other);
        }

        @Specialization
        Object doGeneric(Object self, Object other,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringNode castOtherNode,
                        @Cached BranchProfile noStringBranch) {
            String selfStr = castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, __EQ__, self);
            String otherStr;
            try {
                otherStr = castOtherNode.execute(other);
            } catch (CannotCastException e) {
                noStringBranch.enter();
                return PNotImplemented.NOT_IMPLEMENTED;
            }
            return operator(selfStr, otherStr);
        }

        @SuppressWarnings("unused")
        boolean operator(String self, String other) {
            CompilerAsserts.neverPartOfCompilation();
            throw new IllegalStateException("should not be reached");
        }
    }

    @Builtin(name = __CONTAINS__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class ContainsNode extends PythonBinaryBuiltinNode {
        @Specialization
        boolean doit(Object self, Object other,
                        @Cached CastToJavaStringNode castStr) {
            String selfStr = null;
            String otherStr = null;
            try {
                selfStr = castStr.execute(self);
                otherStr = castStr.execute(other);
            } catch (CannotCastException e) {
                throw raise(TypeError, ErrorMessages.REQUIRES_STRING_AS_LEFT_OPERAND, other);
            }
            return op(selfStr, otherStr);
        }

        @TruffleBoundary
        private static boolean op(String left, String right) {
            return left.contains(right);
        }
    }

    @Builtin(name = __EQ__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class EqNode extends BinaryStringOpNode {
        @Override
        boolean operator(String self, String other) {
            return self.equals(other);
        }
    }

    @Builtin(name = __NE__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class NeNode extends BinaryStringOpNode {
        @Override
        boolean operator(String self, String other) {
            return !self.equals(other);
        }
    }

    @Builtin(name = __LT__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class LtNode extends BinaryStringOpNode {
        @Override
        boolean operator(String self, String other) {
            return StringUtils.compareToUnicodeAware(self, other) < 0;
        }
    }

    @Builtin(name = __LE__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class LeNode extends BinaryStringOpNode {
        @Override
        boolean operator(String self, String other) {
            return StringUtils.compareToUnicodeAware(self, other) <= 0;
        }
    }

    @Builtin(name = __GT__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class GtNode extends BinaryStringOpNode {
        @Override
        boolean operator(String self, String other) {
            return StringUtils.compareToUnicodeAware(self, other) > 0;
        }
    }

    @Builtin(name = __GE__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class GeNode extends BinaryStringOpNode {
        @Override
        boolean operator(String self, String other) {
            return StringUtils.compareToUnicodeAware(self, other) >= 0;
        }
    }

    @Builtin(name = __ADD__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class AddNode extends PythonBinaryBuiltinNode {

        protected final ConditionProfile leftProfile1 = ConditionProfile.createBinaryProfile();
        protected final ConditionProfile leftProfile2 = ConditionProfile.createBinaryProfile();
        protected final ConditionProfile rightProfile1 = ConditionProfile.createBinaryProfile();
        protected final ConditionProfile rightProfile2 = ConditionProfile.createBinaryProfile();

        public static AddNode create() {
            return StringBuiltinsFactory.AddNodeFactory.create();
        }

        @Specialization(guards = "!concatGuard(self, other)")
        String doSSSimple(String self, String other) {
            if (LazyString.length(self, leftProfile1, leftProfile2) == 0) {
                return other;
            }
            return self;
        }

        @Specialization(guards = "!concatGuard(self, other.getCharSequence())", limit = "1")
        Object doSSSimple(String self, PString other,
                        @Cached StringMaterializeNode materializeNode,
                        @Cached IsSameTypeNode isSameType,
                        @Cached BranchProfile isSameBranch,
                        @CachedLibrary("other") PythonObjectLibrary lib) {
            if (LazyString.length(self, leftProfile1, leftProfile2) == 0) {
                // result type has to be str
                if (isSameType.execute(lib.getLazyPythonClass(other), PythonBuiltinClassType.PString)) {
                    isSameBranch.enter();
                    return other;
                }
                return materializeNode.execute(other);
            }
            return self;
        }

        @Specialization(guards = "!concatGuard(self.getCharSequence(), other)", limit = "1")
        Object doSSSimple(PString self, String other,
                        @Cached StringMaterializeNode materializeNode,
                        @Cached IsSameTypeNode isSameType,
                        @Cached BranchProfile isSameBranch,
                        @CachedLibrary("self") PythonObjectLibrary lib) {
            if (LazyString.length(self.getCharSequence(), leftProfile1, leftProfile2) == 0) {
                return other;
            }
            // result type has to be str
            if (isSameType.execute(lib.getLazyPythonClass(self), PythonBuiltinClassType.PString)) {
                isSameBranch.enter();
                return self;
            }
            return materializeNode.execute(self);
        }

        @Specialization(guards = "!concatGuard(self.getCharSequence(), other.getCharSequence())")
        Object doSSSimple(PString self, PString other,
                        @Cached StringMaterializeNode materializeNode,
                        @Cached IsSameTypeNode isSameType,
                        @Cached BranchProfile isSameBranch,
                        @CachedLibrary(limit = "1") PythonObjectLibrary lib) {
            if (LazyString.length(self.getCharSequence(), leftProfile1, leftProfile2) == 0) {
                if (isSameType.execute(lib.getLazyPythonClass(other), PythonBuiltinClassType.PString)) {
                    isSameBranch.enter();
                    return other;
                }
                return materializeNode.execute(other);
            }
            if (isSameType.execute(lib.getLazyPythonClass(self), PythonBuiltinClassType.PString)) {
                isSameBranch.enter();
                return self;
            }
            return materializeNode.execute(self);
        }

        @Specialization(guards = "concatGuard(self.getCharSequence(), other)")
        Object doSS(PString self, String other,
                        @Cached("createBinaryProfile()") ConditionProfile shortStringAppend) {
            return doIt(self.getCharSequence(), other, shortStringAppend);
        }

        @Specialization(guards = "concatGuard(self, other)")
        Object doSS(String self, String other,
                        @Cached("createBinaryProfile()") ConditionProfile shortStringAppend) {
            return doIt(self, other, shortStringAppend);
        }

        @Specialization(guards = "concatGuard(self, other.getCharSequence())")
        Object doSS(String self, PString other,
                        @Cached("createBinaryProfile()") ConditionProfile shortStringAppend) {
            return doIt(self, other.getCharSequence(), shortStringAppend);
        }

        @Specialization(guards = "concatGuard(self.getCharSequence(), other.getCharSequence())")
        Object doSS(PString self, PString other,
                        @Cached("createBinaryProfile()") ConditionProfile shortStringAppend) {
            return doIt(self.getCharSequence(), other.getCharSequence(), shortStringAppend);
        }

        private Object doIt(CharSequence left, CharSequence right, ConditionProfile shortStringAppend) {
            if (getContext().getOption(PythonOptions.LazyStrings)) {
                int leftLength = LazyString.length(left, leftProfile1, leftProfile2);
                int rightLength = LazyString.length(right, rightProfile1, rightProfile2);
                int resultLength = leftLength + rightLength;
                Integer minLazyStringLength = getContext().getOption(PythonOptions.MinLazyStringLength);
                if (resultLength >= minLazyStringLength) {
                    if (shortStringAppend.profile(leftLength == 1 || rightLength == 1)) {
                        return factory().createString(LazyString.createCheckedShort(left, right, resultLength, minLazyStringLength));
                    } else {
                        return factory().createString(LazyString.createChecked(left, right, resultLength));
                    }
                }
            }
            return stringConcat(left, right);
        }

        @TruffleBoundary
        private static String stringConcat(CharSequence left, CharSequence right) {
            return left.toString() + right.toString();
        }

        @Specialization(guards = "isString(self)")
        Object doSNative(VirtualFrame frame, Object self, PythonAbstractNativeObject other,
                        @Cached CastToJavaStringNode cast,
                        @Cached AddNode recurse) {
            try {
                return recurse.execute(frame, self, cast.execute(other));
            } catch (CannotCastException e) {
                throw raise(TypeError, ErrorMessages.CAN_ONLY_CONCAT_S_NOT_P_TO_S, "str", other, "str");
            }
        }

        @Specialization
        Object doNative(VirtualFrame frame, PythonAbstractNativeObject self, PythonAbstractNativeObject other,
                        @Cached CastToJavaStringNode cast,
                        @Cached AddNode recurse) {
            try {
                return recurse.execute(frame, cast.execute(self), cast.execute(other));
            } catch (CannotCastException e) {
                throw raise(TypeError, ErrorMessages.DESCRIPTOR_REQUIRES_OBJ, __ADD__, "str", self);
            }
        }

        @Specialization(guards = {"isString(self)", "!isString(other)", "!isNativeObject(other)"})
        Object doSO(@SuppressWarnings("unused") Object self, Object other) {
            throw raise(TypeError, ErrorMessages.CAN_ONLY_CONCAT_S_NOT_P_TO_S, "str", other, "str");
        }

        @Specialization(guards = {"!isString(self)", "!isNativeObject(self)", "!isNativeObject(other)"})
        Object doNoString(Object self, @SuppressWarnings("unused") Object other) {
            throw raise(TypeError, ErrorMessages.DESCRIPTOR_REQUIRES_OBJ, __ADD__, "str", self);
        }

        protected boolean concatGuard(CharSequence left, CharSequence right) {
            int leftLength = LazyString.length(left, leftProfile1, leftProfile2);
            int rightLength = LazyString.length(right, rightProfile1, rightProfile2);
            return leftLength > 0 && rightLength > 0;
        }
    }

    abstract static class PrefixSuffixBaseNode extends PythonQuaternaryBuiltinNode {

        @Child private CastToSliceComponentNode castSliceComponentNode;
        @Child private GetObjectArrayNode getObjectArrayNode;
        @Child private CastToJavaStringNode castToJavaStringNode;

        // common and specialized cases --------------------

        @Specialization
        boolean doStringPrefixStartEnd(String self, String substr, int start, int end) {
            int len = self.length();
            return doIt(self, substr, adjustStart(start, len), adjustStart(end, len));
        }

        @Specialization
        boolean doStringPrefixStart(String self, String substr, int start, @SuppressWarnings("unused") PNone end) {
            int len = self.length();
            return doIt(self, substr, adjustStart(start, len), len);
        }

        @Specialization
        boolean doStringPrefix(String self, String substr, @SuppressWarnings("unused") PNone start, @SuppressWarnings("unused") PNone end) {
            return doIt(self, substr, 0, self.length());
        }

        @Specialization
        boolean doTuplePrefixStartEnd(String self, PTuple substrs, int start, int end) {
            int len = self.length();
            return doIt(self, substrs, adjustStart(start, len), adjustStart(end, len));
        }

        @Specialization
        boolean doTuplePrefixStart(String self, PTuple substrs, int start, @SuppressWarnings("unused") PNone end) {
            int len = self.length();
            return doIt(self, substrs, adjustStart(start, len), len);
        }

        @Specialization
        boolean doTuplePrefix(String self, PTuple substrs, @SuppressWarnings("unused") PNone start, @SuppressWarnings("unused") PNone end) {
            return doIt(self, substrs, 0, self.length());
        }

        // generic cases --------------------

        @Specialization(guards = "!isPTuple(substr)", replaces = {"doStringPrefixStartEnd", "doStringPrefixStart", "doStringPrefix"})
        boolean doObjectPrefixGeneric(VirtualFrame frame, Object self, Object substr, Object start, Object end,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringCheckedNode castPrefixNode) {
            String selfStr = castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "startswith", self);
            int len = selfStr.length();
            int istart = adjustStart(castSlicePart(frame, start), len);
            int iend = PGuards.isPNone(end) ? len : adjustEnd(castSlicePart(frame, end), len);
            String prefixStr = castPrefixNode.cast(substr, ErrorMessages.FIRST_ARG_MUST_BE_S_OR_TUPLE_NOT_P, "startswith", "str", substr);
            return doIt(selfStr, prefixStr, istart, iend);
        }

        @Specialization(replaces = {"doTuplePrefixStartEnd", "doTuplePrefixStart", "doTuplePrefix"})
        boolean doTuplePrefixGeneric(VirtualFrame frame, Object self, PTuple substrs, Object start, Object end,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            String selfStr = castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "startswith", self);
            int len = selfStr.length();
            int istart = adjustStart(castSlicePart(frame, start), len);
            int iend = PGuards.isPNone(end) ? len : adjustEnd(castSlicePart(frame, end), len);
            return doIt(selfStr, substrs, istart, iend);
        }

        // the actual operation; will be overridden by subclasses
        @SuppressWarnings("unused")
        protected boolean doIt(String text, String substr, int start, int stop) {
            CompilerAsserts.neverPartOfCompilation();
            throw new IllegalStateException("should not reach");
        }

        private boolean doIt(String self, PTuple substrs, int start, int stop) {
            for (Object element : ensureGetObjectArrayNode().execute(substrs)) {
                try {
                    String elementStr = castPrefix(element);
                    if (doIt(self, elementStr, start, stop)) {
                        return true;
                    }
                } catch (CannotCastException e) {
                    throw raise(TypeError, getErrorMessage(), element);
                }
            }
            return false;
        }

        protected String getErrorMessage() {
            CompilerAsserts.neverPartOfCompilation();
            throw new IllegalStateException("should not reach");
        }

        // helper methods --------------------

        // for semantics, see macro 'ADJUST_INDICES' in CPython's 'unicodeobject.c'
        static int adjustStart(int start, int length) {
            if (start < 0) {
                int adjusted = start + length;
                return adjusted < 0 ? 0 : adjusted;
            }
            return start;
        }

        // for semantics, see macro 'ADJUST_INDICES' in CPython's 'unicodeobject.c'
        static int adjustEnd(int end, int length) {
            if (end > length) {
                return length;
            }
            return adjustStart(end, length);
        }

        private int castSlicePart(VirtualFrame frame, Object idx) {
            if (castSliceComponentNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                // None should map to 0, overflow to the maximum integer
                castSliceComponentNode = insert(CastToSliceComponentNode.create(0, Integer.MAX_VALUE));
            }
            return castSliceComponentNode.execute(frame, idx);
        }

        private GetObjectArrayNode ensureGetObjectArrayNode() {
            if (getObjectArrayNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getObjectArrayNode = insert(GetObjectArrayNodeGen.create());
            }
            return getObjectArrayNode;
        }

        private String castPrefix(Object prefix) {
            if (castToJavaStringNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                castToJavaStringNode = insert(CastToJavaStringNodeGen.create());
            }
            return castToJavaStringNode.execute(prefix);
        }

    }

    // str.startswith(prefix[, start[, end]])
    @Builtin(name = "startswith", minNumOfPositionalArgs = 2, parameterNames = {"self", "prefix", "start", "end"})
    @GenerateNodeFactory
    public abstract static class StartsWithNode extends PrefixSuffixBaseNode {

        private static final String INVALID_ELEMENT_TYPE = "tuple for startswith must only contain str, not %p";

        @Override
        protected boolean doIt(String text, String prefix, int start, int end) {
            // start and end must be normalized indices for 'text'
            assert start >= 0;
            assert end >= 0 && end <= text.length();

            if (end - start < prefix.length()) {
                return false;
            }
            return text.startsWith(prefix, start);
        }

        @Override
        protected String getErrorMessage() {
            return INVALID_ELEMENT_TYPE;
        }
    }

    // str.endswith(suffix[, start[, end]])
    @Builtin(name = "endswith", minNumOfPositionalArgs = 2, parameterNames = {"self", "suffix", "start", "end"})
    @GenerateNodeFactory
    public abstract static class EndsWithNode extends PrefixSuffixBaseNode {

        private static final String INVALID_ELEMENT_TYPE = "tuple for endswith must only contain str, not %p";

        @Override
        protected boolean doIt(String text, String suffix, int start, int end) {
            // start and end must be normalized indices for 'text'
            assert start >= 0;
            assert end >= 0 && end <= text.length();

            int suffixLen = suffix.length();
            if (end - start < suffixLen) {
                return false;
            }
            return text.startsWith(suffix, end - suffixLen);
        }

        @Override
        protected String getErrorMessage() {
            return INVALID_ELEMENT_TYPE;
        }
    }

    // str.rfind(str[, start[, end]])
    @Builtin(name = "rfind", minNumOfPositionalArgs = 2, parameterNames = {"$self", "sub", "start", "end"})
    @ArgumentClinic(name = "start", conversion = ArgumentClinic.ClinicConversion.SliceIndex, defaultValue = "0", useDefaultForNone = true)
    @ArgumentClinic(name = "end", conversion = ArgumentClinic.ClinicConversion.SliceIndex, defaultValue = "Integer.MAX_VALUE", useDefaultForNone = true)
    @GenerateNodeFactory
    public abstract static class RFindNode extends PythonQuaternaryClinicBuiltinNode {

        @Override
        protected ArgumentClinicProvider getArgumentClinic() {
            return StringBuiltinsClinicProviders.RFindNodeClinicProviderGen.INSTANCE;
        }

        @Specialization
        public int rfind(String self, String sub, int start, int end,
                        @Cached StringNodes.RFindNode rFindNode) {
            int len = self.length();
            int begin = adjustStartIndex(start, len);
            int last = adjustEndIndex(end, len);
            return rFindNode.execute(self, sub, begin, last);
        }

        @Specialization
        public int rfind(Object self, Object sub, int start, int end,
                        @Cached CastToJavaStringCheckedNode castNode,
                        @Cached StringNodes.RFindNode rFindNode) {
            String strSelf = castNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "rfind", self);
            String subStr = castNode.cast(sub, ErrorMessages.MUST_BE_STR_NOT_P, sub);
            int len = strSelf.length();
            int begin = adjustStartIndex(start, len);
            int last = adjustEndIndex(end, len);
            return rFindNode.execute(strSelf, subStr, begin, last);
        }
    }

    // str.find(str[, start[, end]])
    @Builtin(name = "find", minNumOfPositionalArgs = 2, parameterNames = {"$self", "sub", "start", "end"})
    @ArgumentClinic(name = "start", conversion = ArgumentClinic.ClinicConversion.SliceIndex, defaultValue = "0", useDefaultForNone = true)
    @ArgumentClinic(name = "end", conversion = ArgumentClinic.ClinicConversion.SliceIndex, defaultValue = "Integer.MAX_VALUE", useDefaultForNone = true)
    @GenerateNodeFactory
    public abstract static class FindNode extends PythonQuaternaryClinicBuiltinNode {

        @Override
        protected ArgumentClinicProvider getArgumentClinic() {
            return StringBuiltinsClinicProviders.FindNodeClinicProviderGen.INSTANCE;
        }

        @Specialization
        public int find(String self, String substr, int start, int end,
                        @Cached StringNodes.FindNode findNode) {
            int len = self.length();
            int begin = adjustStartIndex(start, len);
            int last = adjustEndIndex(end, len);
            return findNode.execute(self, substr, begin, last);
        }

        @Specialization
        public int find(Object self, Object sub, int start, int end,
                        @Cached CastToJavaStringCheckedNode castNode,
                        @Cached StringNodes.FindNode findNode) {
            String strSelf = castNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "find", self);
            String subStr = castNode.cast(sub, ErrorMessages.MUST_BE_STR_NOT_P, sub);
            int len = strSelf.length();
            int begin = adjustStartIndex(start, len);
            int last = adjustEndIndex(end, len);
            return findNode.execute(strSelf, subStr, begin, last);
        }
    }

    // str.join(iterable)
    @Builtin(name = "join", minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class JoinNode extends PythonBinaryBuiltinNode {

        @Specialization
        static String join(VirtualFrame frame, Object self, Object iterable,
                        @Cached CastToJavaStringCheckedNode castToJavaStringNode,
                        @Cached JoinInternalNode join) {
            return join.execute(frame, castToJavaStringNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "join", self), iterable);
        }
    }

    // str.upper()
    @Builtin(name = "upper", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class UpperNode extends PythonBuiltinNode {

        @Specialization
        static String upper(Object self,
                        @Cached CastToJavaStringCheckedNode castToJavaStringNode) {
            return toUpperCase(castToJavaStringNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "upper", self));
        }

        @TruffleBoundary
        private static String toUpperCase(String str) {
            return UCharacter.toUpperCase(Locale.ROOT, str);
        }
    }

    // str.maketrans()
    @Builtin(name = "maketrans", minNumOfPositionalArgs = 2, maxNumOfPositionalArgs = 4, isClassmethod = true)
    @GenerateNodeFactory
    public abstract static class MakeTransNode extends PythonQuaternaryBuiltinNode {

        @Specialization(guards = "!isNoValue(to)")
        @SuppressWarnings("unused")
        PDict doString(Object cls, Object from, Object to, Object z,
                        @CachedLanguage PythonLanguage lang,
                        @Cached CastToJavaStringCheckedNode castFromNode,
                        @Cached CastToJavaStringCheckedNode castToNode,
                        @Cached CastToJavaStringCheckedNode castZNode,
                        @Cached ConditionProfile hasZProfile,
                        @CachedLibrary(limit = "2") HashingStorageLibrary lib) {

            String toStr = castToNode.cast(to, "argument 2 must be str, not %p", to);
            String fromStr = castFromNode.cast(from, "first maketrans argument must be a string if there is a second argument");
            boolean hasZ = hasZProfile.profile(z != PNone.NO_VALUE);
            String zString = null;
            if (hasZ) {
                zString = castZNode.cast(z, ErrorMessages.ARG_D_MUST_BE_S_NOT_P, "maketrans()", 3, "str", z);
            }

            HashingStorage storage = PDict.createNewStorage(lang, false, fromStr.length());
            int i, j;
            for (i = 0, j = 0; i < fromStr.length() && j < toStr.length();) {
                int key = PString.codePointAt(fromStr, i);
                int value = PString.codePointAt(toStr, j);
                storage = lib.setItem(storage, key, value);
                i += PString.charCount(key);
                j += PString.charCount(value);
            }
            if (i < fromStr.length() || j < toStr.length()) {
                throw raise(PythonBuiltinClassType.ValueError, ErrorMessages.FIRST_TWO_MAKETRANS_ARGS_MUST_HAVE_EQ_LENGTH);
            }
            if (hasZ) {
                for (i = 0; i < zString.length();) {
                    int key = PString.codePointAt(zString, i);
                    storage = lib.setItem(storage, key, PNone.NONE);
                    i += PString.charCount(key);
                }
            }

            return factory().createDict(storage);
        }

        @Specialization(guards = {"isNoValue(to)", "isNoValue(z)"})
        @SuppressWarnings("unused")
        PDict doDict(VirtualFrame frame, Object cls, PDict from, Object to, Object z,
                        @CachedLanguage PythonLanguage lang,
                        @Cached HashingCollectionNodes.GetHashingStorageNode getHashingStorageNode,
                        @Cached CastToJavaStringCheckedNode cast,
                        @CachedLibrary(limit = "3") HashingStorageLibrary hlib) {
            HashingStorage srcStorage = getHashingStorageNode.execute(frame, from);
            HashingStorage destStorage = PDict.createNewStorage(lang, false, hlib.length(srcStorage));
            for (HashingStorage.DictEntry entry : hlib.entries(srcStorage)) {
                if (PGuards.isInteger(entry.key) || PGuards.isPInt(entry.key)) {
                    destStorage = hlib.setItem(destStorage, entry.key, entry.value);
                } else {
                    String strKey = cast.cast(entry.key, ErrorMessages.KEYS_IN_TRANSLATE_TABLE_MUST_BE_STRINGS_OR_INTEGERS);
                    if (strKey.isEmpty()) {
                        throw raise(ValueError, ErrorMessages.STRING_KEYS_MUST_BE_LENGHT_1);
                    }
                    int codePoint = PString.codePointAt(strKey, 0);
                    if (strKey.length() != PString.charCount(codePoint)) {
                        throw raise(ValueError, ErrorMessages.STRING_KEYS_MUST_BE_LENGHT_1);
                    }
                    destStorage = hlib.setItem(destStorage, codePoint, entry.value);
                }
            }
            return factory().createDict(destStorage);
        }

        @Specialization(guards = {"!isDict(from)", "isNoValue(to)"})
        @SuppressWarnings("unused")
        PDict doFail(Object cls, Object from, Object to, Object z) {
            throw raise(PythonBuiltinClassType.TypeError, ErrorMessages.IF_YOU_GIVE_ONLY_ONE_ARG_TO_DICT);
        }
    }

    // str.translate()
    @Builtin(name = "translate", minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class TranslateNode extends PythonBuiltinNode {
        @Specialization
        static String doStringString(String self, String table) {
            char[] translatedChars = new char[self.length()];

            for (int i = 0; i < self.length(); i++) {
                char original = self.charAt(i);
                char translation = table.charAt(original);
                translatedChars[i] = translation;
            }

            return PythonUtils.newString(translatedChars);
        }

        @Specialization
        static String doGeneric(VirtualFrame frame, Object self, Object table,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached GetItemNode getItemNode,
                        @CachedLibrary(limit = "3") PythonObjectLibrary plib,
                        @Cached IsSubtypeNode isSubtypeNode,
                        @Cached SpliceNode spliceNode) {
            String selfStr = castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "translate", self);

            StringBuilder sb = PythonUtils.newStringBuilder(selfStr.length());

            for (int i = 0; i < selfStr.length();) {
                int original = PString.codePointAt(selfStr, i);
                Object translated = null;
                try {
                    translated = getItemNode.execute(frame, table, original);
                } catch (PException e) {
                    if (!isSubtypeNode.execute(null, plib.getLazyPythonClass(e.getExceptionObject()), PythonBuiltinClassType.LookupError)) {
                        throw e;
                    }
                }
                if (translated != null) {
                    spliceNode.execute(sb, translated);
                } else {
                    PythonUtils.appendCodePoint(sb, original);
                }
                i += PString.charCount(original);
            }

            return PythonUtils.sbToString(sb);
        }
    }

    // str.lower()
    @Builtin(name = "lower", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class LowerNode extends PythonUnaryBuiltinNode {

        @Specialization
        static String doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castToJavaStringNode) {
            return StringUtils.toLowerCase(castToJavaStringNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "lower", self));
        }

    }

    // str.capitalize()
    @Builtin(name = "capitalize", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class CapitalizeNode extends PythonUnaryBuiltinNode {

        @CompilationFinal private static CaseMap.Title titlecaser;

        @Specialization
        static String capitalize(String self) {
            if (self.isEmpty()) {
                return "";
            } else {
                return capitalizeImpl(self);
            }
        }

        @Specialization
        static String doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castToJavaStringNode) {
            return capitalize(castToJavaStringNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "capitalize", self));
        }

        private static String capitalizeImpl(String str) {
            if (titlecaser == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                titlecaser = CaseMap.toTitle().wholeString().noBreakAdjustment();
            }
            return apply(str);
        }

        @TruffleBoundary
        private static String apply(String str) {
            return titlecaser.apply(Locale.ROOT, null, str);
        }
    }

    // str.partition
    @Builtin(name = "partition", minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class PartitionNode extends PythonBinaryBuiltinNode {

        @Specialization
        Object doString(String self, String sep) {
            if (sep.isEmpty()) {
                throw raise(ValueError, ErrorMessages.EMPTY_SEPARATOR);
            }
            int indexOf = self.indexOf(sep);
            String[] partitioned = new String[3];
            if (indexOf == -1) {
                partitioned[0] = self;
                partitioned[1] = "";
                partitioned[2] = "";
            } else {
                partitioned[0] = PString.substring(self, 0, indexOf);
                partitioned[1] = sep;
                partitioned[2] = PString.substring(self, indexOf + sep.length());
            }
            return factory().createTuple(partitioned);
        }

        @Specialization(replaces = "doString")
        Object doGeneric(Object self, Object sep,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringCheckedNode castSepNode) {
            String selfStr = castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "partition", self);
            String sepStr = castSepNode.cast(sep, ErrorMessages.MUST_BE_STR_NOT_P, sep);
            return doString(selfStr, sepStr);
        }
    }

    // str.rpartition
    @Builtin(name = "rpartition", minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class RPartitionNode extends PythonBinaryBuiltinNode {

        @Specialization
        Object doString(String self, String sep) {
            if (sep.isEmpty()) {
                throw raise(ValueError, ErrorMessages.EMPTY_SEPARATOR);
            }
            int lastIndexOf = self.lastIndexOf(sep);
            String[] partitioned = new String[3];
            if (lastIndexOf == -1) {
                partitioned[0] = "";
                partitioned[1] = "";
                partitioned[2] = self;
            } else {
                partitioned[0] = PString.substring(self, 0, lastIndexOf);
                partitioned[1] = sep;
                partitioned[2] = PString.substring(self, lastIndexOf + sep.length());
            }
            return factory().createTuple(partitioned);
        }

        @Specialization(replaces = "doString")
        Object doGeneric(Object self, Object sep,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringCheckedNode castSepNode) {
            String selfStr = castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "rpartition", self);
            String sepStr = castSepNode.cast(sep, ErrorMessages.MUST_BE_STR_NOT_P, sep);
            return doString(selfStr, sepStr);
        }
    }

    // str.split
    @Builtin(name = "split", minNumOfPositionalArgs = 1, parameterNames = {"$self", "sep", "maxsplit"}, needsFrame = true)
    @ArgumentClinic(name = "$self", conversion = ClinicConversion.String)
    @ArgumentClinic(name = "sep", conversion = ClinicConversion.String, defaultValue = "PNone.NONE", useDefaultForNone = true)
    @ArgumentClinic(name = "maxsplit", conversion = ClinicConversion.Index, defaultValue = "-1")
    @GenerateNodeFactory
    public abstract static class SplitNode extends PythonTernaryClinicBuiltinNode {

        @Override
        protected ArgumentClinicProvider getArgumentClinic() {
            return SplitNodeClinicProviderGen.INSTANCE;
        }

        @Specialization
        @SuppressWarnings("unused")
        PList doStringNoSep(String self, PNone sep, int maxsplit,
                        @Shared("appendNode") @Cached AppendNode appendNode) {
            return splitfields(self, maxsplit, appendNode);
        }

        @Specialization
        PList doStringSep(String self, String sep, int maxsplit,
                        @Shared("appendNode") @Cached AppendNode appendNode) {
            if (sep.isEmpty()) {
                throw raise(ValueError, ErrorMessages.EMPTY_SEPARATOR);
            }
            int splits = maxsplit == -1 ? Integer.MAX_VALUE : maxsplit;

            PList list = factory().createList();
            int lastEnd = 0;
            while (splits > 0) {
                int nextIndex = PString.indexOf(self, sep, lastEnd);
                if (nextIndex == -1) {
                    break;
                }
                splits--;
                appendNode.execute(list, PString.substring(self, lastEnd, nextIndex));
                lastEnd = nextIndex + sep.length();
            }
            appendNode.execute(list, self.substring(lastEnd));
            return list;
        }

        // See {@link PyString}
        private PList splitfields(String s, int maxsplit, AppendNode appendNode) {
            /*
             * Result built here is a list of split parts, exactly as required for s.split(None,
             * maxsplit). If there are to be n splits, there will be n+1 elements in L.
             */
            PList list = factory().createList();
            int length = s.length();
            int start = 0;
            int splits = 0;
            int index;

            int maxsplit2 = maxsplit;
            if (maxsplit2 < 0) {
                // Make all possible splits: there can't be more than:
                maxsplit2 = length;
            }

            // start is always the first character not consumed into a piece on the list
            while (start < length) {

                // Find the next occurrence of non-whitespace
                while (start < length) {
                    if (!PString.isWhitespace(s.charAt(start))) {
                        // Break leaving start pointing at non-whitespace
                        break;
                    }
                    start++;
                }

                if (start >= length) {
                    // Only found whitespace so there is no next segment
                    break;

                } else if (splits >= maxsplit2) {
                    // The next segment is the last and contains all characters up to the end
                    index = length;

                } else {
                    // The next segment runs up to the next next whitespace or end
                    for (index = start; index < length; index++) {
                        if (PString.isWhitespace(s.charAt(index))) {
                            // Break leaving index pointing at whitespace
                            break;
                        }
                    }
                }

                // Make a piece from start up to index
                appendNode.execute(list, PString.substring(s, start, index));
                splits++;

                // Start next segment search at that point
                start = index;
            }

            return list;
        }
    }

    // str.rsplit
    @Builtin(name = "rsplit", minNumOfPositionalArgs = 1, parameterNames = {"$self", "sep", "maxsplit"}, needsFrame = true)
    @ArgumentClinic(name = "$self", conversion = ClinicConversion.String)
    @ArgumentClinic(name = "sep", conversion = ClinicConversion.String, defaultValue = "PNone.NONE", useDefaultForNone = true)
    @ArgumentClinic(name = "maxsplit", conversion = ClinicConversion.Index, defaultValue = "-1")
    @GenerateNodeFactory
    public abstract static class RSplitNode extends PythonTernaryClinicBuiltinNode {

        @Override
        protected ArgumentClinicProvider getArgumentClinic() {
            return StringBuiltinsClinicProviders.RSplitNodeClinicProviderGen.INSTANCE;
        }

        @Specialization
        PList doStringSepMaxsplit(VirtualFrame frame, String self, String sep, int maxsplitInput,
                        @Shared("appendNode") @Cached AppendNode appendNode,
                        @Shared("reverseNode") @Cached ListReverseNode reverseNode) {
            if (sep.length() == 0) {
                throw raise(ValueError, ErrorMessages.EMPTY_SEPARATOR);
            }
            int maxsplit = maxsplitInput;
            if (maxsplitInput < 0) {
                maxsplit = Integer.MAX_VALUE;
            }
            PList list = factory().createList();
            int splits = 0;
            int end = self.length();
            String remainder = self;
            int sepLength = sep.length();
            while (splits < maxsplit) {
                int idx = remainder.lastIndexOf(sep);

                if (idx < 0) {
                    break;
                }

                appendNode.execute(list, self.substring(idx + sepLength, end));
                end = idx;
                splits++;
                remainder = remainder.substring(0, end);
            }

            appendNode.execute(list, remainder);
            reverseNode.call(frame, list);
            return list;
        }

        @Specialization
        PList doStringMaxsplit(VirtualFrame frame, String self, @SuppressWarnings("unused") PNone sep, int maxsplit,
                        @Shared("appendNode") @Cached AppendNode appendNode,
                        @Shared("reverseNode") @Cached ListReverseNode reverseNode) {
            return rsplitfields(frame, self, maxsplit, appendNode, reverseNode);
        }

        private PList rsplitfields(VirtualFrame frame, String s, int maxsplit, AppendNode appendNode, ListReverseNode reverseNode) {
            /*
             * Result built here is a list of split parts, exactly as required for s.split(None,
             * maxsplit). If there are to be n splits, there will be n+1 elements in L.
             */
            PList list = factory().createList();
            int length = s.length();

            int maxsplit2 = maxsplit;
            if (maxsplit2 < 0) {
                // Make all possible splits: there can't be more than:
                maxsplit2 = length;
            }

            // 2 state machine - we're either reading whitespace or non-whitespace, segments are
            // emitted in ws->non-ws transition and at the end
            boolean hasSegment = false;
            int start = 0, end = length, splits = 0;

            for (int i = length - 1; i >= 0; i--) {
                if (!PString.isLowSurrogate(s.charAt(i))) {
                    if (StringUtils.isSpace(PString.codePointAt(s, i))) {
                        if (hasSegment) {
                            appendNode.execute(list, s.substring(start, end));
                            hasSegment = false;
                            splits++;
                        }
                        end = i;
                        if (PString.isHighSurrogate(s.charAt(i))) {
                            end++;
                        }
                    } else {
                        hasSegment = true;
                        if (splits >= maxsplit2) {
                            break;
                        }
                        start = i;
                    }
                }
            }
            if (hasSegment) {
                appendNode.execute(list, s.substring(0, end));
            }

            reverseNode.call(frame, list);
            return list;
        }
    }

    // str.splitlines([keepends])
    @Builtin(name = "splitlines", minNumOfPositionalArgs = 1, parameterNames = {"self", "keepends"})
    @GenerateNodeFactory
    public abstract static class SplitLinesNode extends PythonBinaryBuiltinNode {
        @Child private AppendNode appendNode = AppendNode.create();

        private static final Pattern LINEBREAK_PATTERN = Pattern.compile("\\R");

        @Specialization
        PList doString(String self, @SuppressWarnings("unused") PNone keepends) {
            return doStringKeepends(self, false);
        }

        @Specialization
        PList doStringKeepends(String self, boolean keepends) {
            PList list = factory().createList();
            int lastEnd = 0;
            Matcher matcher = getMatcher(self);
            while (matcherFind(matcher)) {
                int end = matcherEnd(matcher);
                if (keepends) {
                    appendNode.execute(list, PString.substring(self, lastEnd, end));
                } else {
                    appendNode.execute(list, PString.substring(self, lastEnd, matcherStart(matcher)));
                }
                lastEnd = end;
            }
            String remainder = PString.substring(self, lastEnd);
            if (!remainder.isEmpty()) {
                appendNode.execute(list, remainder);
            }
            return list;
        }

        @TruffleBoundary
        private static int matcherStart(Matcher matcher) {
            return matcher.start();
        }

        @TruffleBoundary
        private static int matcherEnd(Matcher matcher) {
            return matcher.end();
        }

        @TruffleBoundary
        private static boolean matcherFind(Matcher matcher) {
            return matcher.find();
        }

        @TruffleBoundary
        private static Matcher getMatcher(String self) {
            return LINEBREAK_PATTERN.matcher(self);
        }

        @Specialization(replaces = {"doString", "doStringKeepends"})
        PList doGeneric(Object self, Object keepends,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaIntExactNode castToJavaIntNode) {
            String selfStr = castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "splitlines", self);
            boolean bKeepends = !PGuards.isPNone(keepends) && castToJavaIntNode.execute(keepends) != 0;
            return doStringKeepends(selfStr, bKeepends);
        }
    }

    // str.replace
    @Builtin(name = "replace", minNumOfPositionalArgs = 3, maxNumOfPositionalArgs = 4)
    @GenerateNodeFactory
    public abstract static class ReplaceNode extends PythonBuiltinNode {

        @Specialization
        @TruffleBoundary
        static String doReplace(String self, String old, String with, @SuppressWarnings("unused") PNone maxCount) {
            return self.replace(old, with);
        }

        @TruffleBoundary
        @Specialization
        static String doReplace(String self, String old, String with, int maxCount) {
            if (maxCount < 0) {
                return doReplace(self, old, with, PNone.NO_VALUE);
            }
            StringBuilder sb;
            if (old.isEmpty()) {
                sb = new StringBuilder(self.length() + with.length() * Math.min(maxCount, self.length() + 1));
                int replacements, i;
                for (replacements = 0, i = 0; replacements < maxCount && i < self.length(); replacements++) {
                    sb.append(with);
                    int codePoint = self.codePointAt(i);
                    sb.appendCodePoint(codePoint);
                    i += Character.charCount(codePoint);
                }
                if (replacements < maxCount) {
                    sb.append(with);
                }
                if (i < self.length()) {
                    sb.append(self.substring(i));
                }
            } else {
                sb = new StringBuilder(self);
                int prevIdx = 0;
                for (int i = 0; i < maxCount; i++) {
                    int idx = sb.indexOf(old, prevIdx);
                    if (idx == -1) {
                        // done
                        break;
                    }

                    sb.replace(idx, idx + old.length(), with);
                    prevIdx = idx + with.length();
                }
            }
            return sb.toString();
        }

        @Specialization(limit = "getCallSiteInlineCacheMaxDepth()")
        static String doGeneric(VirtualFrame frame, Object self, Object old, Object with, Object maxCount,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @CachedLibrary("maxCount") PythonObjectLibrary lib) {

            String selfStr = castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "replace", self);
            String oldStr = castSelfNode.cast(old, "replace() argument 1 must be str, not %p", "replace", old);
            String withStr = castSelfNode.cast(with, "replace() argument 2 must be str, not %p", "replace", with);
            if (PGuards.isPNone(maxCount)) {
                return doReplace(selfStr, oldStr, withStr, PNone.NO_VALUE);
            }
            int iMaxCount = lib.asSizeWithState(maxCount, PArguments.getThreadState(frame));
            return doReplace(selfStr, oldStr, withStr, iMaxCount);
        }
    }

    @Builtin(name = "strip", minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class StripNode extends PythonBinaryBuiltinNode {
        @Specialization
        static String doStringString(String self, String chars) {
            return StringUtils.strip(self, chars, StripKind.BOTH);
        }

        @Specialization
        static String doStringNone(String self, @SuppressWarnings("unused") PNone chars) {
            return StringUtils.strip(self, StripKind.BOTH);
        }

        @Specialization(replaces = {"doStringString", "doStringNone"})
        static String doGeneric(Object self, Object chars,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            String selfStr = castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "strip", self);
            if (PGuards.isPNone(chars)) {
                return doStringNone(selfStr, PNone.NO_VALUE);
            }
            String charsStr = castSelfNode.cast(chars, "replace() argument 1 must be str, not %p", "strip", chars);
            return doStringString(selfStr, charsStr);
        }
    }

    @Builtin(name = "rstrip", minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class RStripNode extends PythonBinaryBuiltinNode {
        @Specialization
        static String doStringString(String self, String chars) {
            return StringUtils.strip(self, chars, StripKind.RIGHT);
        }

        @Specialization
        static String doStringNone(String self, @SuppressWarnings("unused") PNone chars) {
            return StringUtils.strip(self, StripKind.RIGHT);
        }

        @Specialization(replaces = {"doStringString", "doStringNone"})
        static String doGeneric(Object self, Object chars,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringCheckedNode castCharsNode) {
            String selfStr = castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "rstrip", self);
            if (PGuards.isPNone(chars)) {
                return doStringNone(selfStr, PNone.NO_VALUE);
            }
            String charsStr = castCharsNode.cast(chars, "replace() argument 1 must be str, not %p", "rstrip", chars);
            return doStringString(selfStr, charsStr);
        }
    }

    @Builtin(name = "lstrip", minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class LStripNode extends PythonBuiltinNode {
        @Specialization
        static String doStringString(String self, String chars) {
            return StringUtils.strip(self, chars, StripKind.LEFT);
        }

        @Specialization
        static String doStringNone(String self, @SuppressWarnings("unused") PNone chars) {
            return StringUtils.strip(self, StripKind.LEFT);
        }

        @Specialization(replaces = {"doStringString", "doStringNone"})
        static String doGeneric(Object self, Object chars,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringCheckedNode castCharsNode) {
            String selfStr = castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "lstrip", self);
            if (PGuards.isPNone(chars)) {
                return doStringNone(selfStr, PNone.NO_VALUE);
            }
            String charsStr = castCharsNode.cast(chars, "replace() argument 1 must be str, not %p", "lstrip", chars);
            return doStringString(selfStr, charsStr);
        }
    }

    @Builtin(name = SpecialMethodNames.__LEN__, minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class LenNode extends PythonUnaryBuiltinNode {
        @Specialization
        static int len(Object self,
                        @Cached StringLenNode stringLenNode) {
            return stringLenNode.execute(self);
        }
    }

    @Builtin(name = "index", minNumOfPositionalArgs = 2, parameterNames = {"$self", "sub", "start", "end"})
    @ArgumentClinic(name = "start", conversion = ArgumentClinic.ClinicConversion.SliceIndex, defaultValue = "0", useDefaultForNone = true)
    @ArgumentClinic(name = "end", conversion = ArgumentClinic.ClinicConversion.SliceIndex, defaultValue = "Integer.MAX_VALUE", useDefaultForNone = true)
    @GenerateNodeFactory
    public abstract static class IndexNode extends PythonQuaternaryClinicBuiltinNode {
        @Override
        protected ArgumentClinicProvider getArgumentClinic() {
            return StringBuiltinsClinicProviders.IndexNodeClinicProviderGen.INSTANCE;
        }

        @Specialization
        public int index(String self, String sub, int start, int end,
                        @Cached StringNodes.FindNode findNode) {
            int len = self.length();
            int begin = adjustStartIndex(start, len);
            int last = adjustEndIndex(end, len);
            int idx = findNode.execute(self, sub, begin, last);
            if (idx < 0) {
                throw raise(ValueError, ErrorMessages.SUBSTRING_NOT_FOUND);
            }
            return idx;
        }

        @Specialization
        public int index(Object self, Object sub, int start, int end,
                        @Cached CastToJavaStringCheckedNode castNode,
                        @Cached StringNodes.FindNode findNode) {
            String strSelf = castNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "index", self);
            String subStr = castNode.cast(sub, ErrorMessages.MUST_BE_STR_NOT_P, sub);
            int len = strSelf.length();
            int begin = adjustStartIndex(start, len);
            int last = adjustEndIndex(end, len);
            int idx = findNode.execute(strSelf, subStr, begin, last);
            if (idx < 0) {
                throw raise(ValueError, ErrorMessages.SUBSTRING_NOT_FOUND);
            }
            return idx;
        }
    }

    @Builtin(name = "rindex", minNumOfPositionalArgs = 2, parameterNames = {"$self", "sub", "start", "end"})
    @ArgumentClinic(name = "start", conversion = ArgumentClinic.ClinicConversion.SliceIndex, defaultValue = "0", useDefaultForNone = true)
    @ArgumentClinic(name = "end", conversion = ArgumentClinic.ClinicConversion.SliceIndex, defaultValue = "Integer.MAX_VALUE", useDefaultForNone = true)
    @GenerateNodeFactory
    public abstract static class RIndexNode extends PythonQuaternaryClinicBuiltinNode {
        @Override
        protected ArgumentClinicProvider getArgumentClinic() {
            return StringBuiltinsClinicProviders.RIndexNodeClinicProviderGen.INSTANCE;
        }

        @Specialization
        public int rindex(String self, String sub, int start, int end,
                        @Cached StringNodes.RFindNode rFindNode) {
            int len = self.length();
            int begin = adjustStartIndex(start, len);
            int last = adjustEndIndex(end, len);
            int idx = rFindNode.execute(self, sub, begin, last);
            if (idx < 0) {
                throw raise(ValueError, ErrorMessages.SUBSTRING_NOT_FOUND);
            }
            return idx;
        }

        @Specialization
        public int rindex(Object self, Object sub, int start, int end,
                        @Cached CastToJavaStringCheckedNode castNode,
                        @Cached StringNodes.RFindNode rFindNode) {
            String strSelf = castNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "rindex", self);
            String subStr = castNode.cast(sub, ErrorMessages.MUST_BE_STR_NOT_P, sub);
            int len = strSelf.length();
            int begin = adjustStartIndex(start, len);
            int last = adjustEndIndex(end, len);
            int idx = rFindNode.execute(strSelf, subStr, begin, last);
            if (idx < 0) {
                throw raise(ValueError, ErrorMessages.SUBSTRING_NOT_FOUND);
            }
            return idx;
        }
    }

    // This is only used during bootstrap and then replaced with Python code
    @Builtin(name = "encode", minNumOfPositionalArgs = 1, parameterNames = {"self", "encoding", "errors"})
    @GenerateNodeFactory
    @TypeSystemReference(PythonArithmeticTypes.class)
    public abstract static class EncodeNode extends PythonBuiltinNode {

        @Specialization
        Object doString(String self, @SuppressWarnings("unused") PNone encoding, @SuppressWarnings("unused") PNone errors) {
            return encodeString(self, "utf-8", "strict");
        }

        @Specialization
        Object doStringEncoding(String self, String encoding, @SuppressWarnings("unused") PNone errors) {
            return encodeString(self, encoding, "strict");
        }

        @Specialization
        Object doStringErrors(String self, @SuppressWarnings("unused") PNone encoding, String errors) {
            return encodeString(self, "utf-8", errors);
        }

        @Specialization
        Object doStringEncodingErrors(String self, String encoding, String errors) {
            return encodeString(self, encoding, errors);
        }

        @Specialization
        Object doGeneric(Object self, Object encoding, Object errors,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringCheckedNode castEncodingNode,
                        @Cached CastToJavaStringCheckedNode castErrorsNode) {
            String selfStr = castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "index", self);
            String encodingStr = PGuards.isPNone(encoding) ? "utf-8" : castEncodingNode.cast(encoding, ErrorMessages.MUST_BE_STR_NOT_P, encoding);
            String errorsStr = PGuards.isPNone(errors) ? "strict" : castErrorsNode.cast(errors, ErrorMessages.MUST_BE_STR_NOT_P, errors);
            return encodeString(selfStr, encodingStr, errorsStr);
        }

        @TruffleBoundary
        private Object encodeString(String self, String encoding, String errors) {
            // Note: to support custom actions, we can use CharsetEncoderICU from icu4j-charset
            CodingErrorAction errorAction;
            switch (errors) {
                case "ignore":
                    errorAction = CodingErrorAction.IGNORE;
                    break;
                case "replace":
                    errorAction = CodingErrorAction.REPLACE;
                    break;
                default:
                    errorAction = CodingErrorAction.REPORT;
                    break;
            }

            Charset cs;
            try {
                cs = Charset.forName(encoding);
            } catch (UnsupportedCharsetException | IllegalCharsetNameException e) {
                throw raise(LookupError, ErrorMessages.UNKNOWN_ENCODING, encoding);
            }
            try {
                ByteBuffer encoded = cs.newEncoder().onMalformedInput(errorAction).onUnmappableCharacter(errorAction).encode(CharBuffer.wrap(self));
                int n = encoded.remaining();
                byte[] data = new byte[n];
                encoded.get(data);
                return factory().createBytes(data);
            } catch (CharacterCodingException e) {
                throw raise(UnicodeEncodeError, e);
            }
        }
    }

    @Builtin(name = __RMUL__, minNumOfPositionalArgs = 2)
    @Builtin(name = __MUL__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    @TypeSystemReference(PythonArithmeticTypes.class)
    abstract static class MulNode extends PythonBinaryBuiltinNode {

        @Specialization(guards = "right <= 0")
        String doEmptyStringInt(@SuppressWarnings("unused") Object left, @SuppressWarnings("unused") int right) {
            return "";
        }

        @Specialization(guards = {"left.length() == 0", "right > 0"})
        String doEmptyStringInt(String left, @SuppressWarnings("unused") int right) {
            return left;
        }

        @Specialization(guards = {"left.length() == 1", "right > 0"})
        String doCharInt(String left, int right) {
            try {
                char[] result = new char[right];
                Arrays.fill(result, left.charAt(0));
                return PythonUtils.newString(result);
            } catch (OutOfMemoryError e) {
                throw raise(MemoryError);
            }
        }

        @Specialization(guards = {"left.length() > 1", "right > 0"})
        String doStringInt(String left, int right,
                        @Shared("loopProfile") @Cached("createCountingProfile()") LoopConditionProfile loopProfile) {
            return repeatString(left, right, loopProfile);
        }

        @Specialization(limit = "1")
        String doStringLong(String left, long right,
                        @Shared("loopProfile") @Cached("createCountingProfile()") LoopConditionProfile loopProfile,
                        @Exclusive @CachedLibrary("right") PythonObjectLibrary lib) {
            return doStringIntGeneric(left, lib.asSize(right), loopProfile);
        }

        @Specialization
        String doStringObject(VirtualFrame frame, String left, Object right,
                        @Cached("createBinaryProfile()") ConditionProfile hasFrame,
                        @Shared("loopProfile") @Cached("createCountingProfile()") LoopConditionProfile loopProfile,
                        @Shared("castToIndexNode") @CachedLibrary(limit = "getCallSiteInlineCacheMaxDepth()") PythonObjectLibrary lib) {
            int repeat;
            if (hasFrame.profile(frame != null)) {
                repeat = lib.asSizeWithState(right, PArguments.getThreadState(frame));
            } else {
                repeat = lib.asSize(right);
            }
            return doStringIntGeneric(left, repeat, loopProfile);
        }

        @Specialization
        Object doGeneric(VirtualFrame frame, Object self, Object times,
                        @Shared("loopProfile") @Cached("createCountingProfile()") LoopConditionProfile loopProfile,
                        @Cached("createBinaryProfile()") ConditionProfile hasFrame,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Shared("castToIndexNode") @CachedLibrary(limit = "getCallSiteInlineCacheMaxDepth()") PythonObjectLibrary lib) {
            String selfStr = castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "index", self);
            return doStringObject(frame, selfStr, times, hasFrame, loopProfile, lib);
        }

        public String doStringIntGeneric(String left, int right, LoopConditionProfile loopProfile) {
            if (right <= 0) {
                return "";
            }
            return repeatString(left, right, loopProfile);
        }

        private String repeatString(String left, int times, LoopConditionProfile loopProfile) {
            try {
                int total;
                try {
                    total = PythonUtils.multiplyExact(left.length(), times);
                } catch (OverflowException ex) {
                    throw raise(MemoryError);
                }
                char[] result = new char[total];
                PythonUtils.getChars(left, 0, left.length(), result, 0);
                int done = left.length();
                while (loopProfile.profile(done < total)) {
                    int todo = total - done;
                    int len = Math.min(done, todo);
                    PythonUtils.arraycopy(result, 0, result, done, len);
                    done += len;
                }
                return PythonUtils.newString(result);
            } catch (OutOfMemoryError e) {
                throw raise(MemoryError);
            }
        }

    }

    @Builtin(name = __MOD__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class ModNode extends PythonBinaryBuiltinNode {

        @Specialization
        Object doStringObject(VirtualFrame frame, String self, Object right,
                        @Shared("getItemNode") @Cached("create(__GETITEM__)") LookupAndCallBinaryNode getItemNode,
                        @Shared("getTupleItemNode") @Cached TupleBuiltins.GetItemNode getTupleItemNode,
                        @Shared("context") @CachedContext(PythonLanguage.class) PythonContext context) {
            Object state = IndirectCallContext.enter(frame, context, this);
            try {
                return new StringFormatProcessor(context.getCore(), getRaiseNode(), getItemNode, getTupleItemNode, self).format(right);
            } finally {
                IndirectCallContext.exit(frame, context, state);
            }
        }

        @Specialization
        Object doGeneric(VirtualFrame frame, Object self, Object right,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Shared("getItemNode") @Cached("create(__GETITEM__)") LookupAndCallBinaryNode getItemNode,
                        @Shared("getTupleItemNode") @Cached TupleBuiltins.GetItemNode getTupleItemNode,
                        @Shared("context") @CachedContext(PythonLanguage.class) PythonContext context) {

            String selfStr = castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, __MOD__, self);
            return doStringObject(frame, selfStr, right, getItemNode, getTupleItemNode, context);
        }
    }

    @Builtin(name = "isascii", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsAsciiNode extends PythonUnaryBuiltinNode {
        private static final CharsetEncoder asciiEncoder = StandardCharsets.US_ASCII.newEncoder();

        @Specialization
        @TruffleBoundary
        boolean doString(String self) {
            return asciiEncoder.canEncode(self);
        }

        @Specialization(replaces = "doString")
        boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "isascii", self));
        }
    }

    abstract static class IsCategoryBaseNode extends PythonUnaryBuiltinNode {
        @Specialization
        @TruffleBoundary
        boolean doString(String self) {
            if (self.length() == 0) {
                return false;
            }
            for (int i = 0; i < self.length();) {
                int codePoint = self.codePointAt(i);
                if (!isCategory(codePoint)) {
                    return false;
                }
                i += Character.charCount(codePoint);
            }
            return true;
        }

        @Specialization(replaces = "doString")
        boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, getName(), self));
        }

        @SuppressWarnings("unused")
        protected boolean isCategory(int codePoint) {
            CompilerAsserts.neverPartOfCompilation();
            throw new IllegalStateException("should not be reached");
        }

        protected String getName() {
            CompilerAsserts.neverPartOfCompilation();
            throw new IllegalStateException("should not be reached");
        }
    }

    @Builtin(name = "isalnum", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsAlnumNode extends IsCategoryBaseNode {
        @Override
        protected boolean isCategory(int codePoint) {
            return StringUtils.isAlnum(codePoint);
        }

        @Override
        protected String getName() {
            return "isalnum";
        }
    }

    @Builtin(name = "isalpha", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsAlphaNode extends IsCategoryBaseNode {
        @Override
        protected boolean isCategory(int codePoint) {
            return UCharacter.isLetter(codePoint);
        }

        @Override
        protected String getName() {
            return "isalpha";
        }
    }

    @Builtin(name = "isdecimal", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsDecimalNode extends IsCategoryBaseNode {
        @Override
        protected boolean isCategory(int codePoint) {
            return UCharacter.isDigit(codePoint);
        }

        @Override
        protected String getName() {
            return "isdecimal";
        }
    }

    @Builtin(name = "isdigit", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsDigitNode extends IsCategoryBaseNode {
        @Override
        protected boolean isCategory(int codePoint) {
            int numericType = UCharacter.getIntPropertyValue(codePoint, UProperty.NUMERIC_TYPE);
            return numericType == UCharacter.NumericType.DECIMAL || numericType == UCharacter.NumericType.DIGIT;
        }

        @Override
        protected String getName() {
            return "isdigit";
        }
    }

    @Builtin(name = "isnumeric", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsNumericNode extends IsCategoryBaseNode {
        @Override
        protected boolean isCategory(int codePoint) {
            int numericType = UCharacter.getIntPropertyValue(codePoint, UProperty.NUMERIC_TYPE);
            return numericType == UCharacter.NumericType.DECIMAL || numericType == UCharacter.NumericType.DIGIT || numericType == UCharacter.NumericType.NUMERIC;
        }

        @Override
        protected String getName() {
            return "isnumeric";
        }
    }

    @Builtin(name = "isidentifier", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsIdentifierNode extends PythonUnaryBuiltinNode {
        @Specialization
        boolean doString(String self) {
            return getCore().getParser().isIdentifier(getCore(), self);
        }

        @Specialization(replaces = "doString")
        boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "isidentifier", self));
        }
    }

    @Builtin(name = "islower", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsLowerNode extends PythonUnaryBuiltinNode {
        @Specialization
        @TruffleBoundary
        static boolean doString(String self) {
            boolean hasLower = false;
            for (int i = 0; i < self.length();) {
                int codePoint = self.codePointAt(i);
                if (UCharacter.isUUppercase(codePoint) || UCharacter.isTitleCase(codePoint)) {
                    return false;
                }
                if (!hasLower && UCharacter.isULowercase(codePoint)) {
                    hasLower = true;
                }
                i += Character.charCount(codePoint);
            }
            return hasLower;
        }

        @Specialization(replaces = "doString")
        static boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "islower", self));
        }
    }

    @Builtin(name = "isprintable", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsPrintableNode extends PythonUnaryBuiltinNode {
        @TruffleBoundary
        private static boolean isPrintableChar(int i) {
            return StringUtils.isPrintable(i);
        }

        @Specialization
        @TruffleBoundary
        static boolean doString(String self) {
            for (int i = 0; i < self.length();) {
                int codePoint = self.codePointAt(i);
                if (!isPrintableChar(codePoint)) {
                    return false;
                }
                i += Character.charCount(codePoint);
            }
            return true;
        }

        @Specialization(replaces = "doString")
        static boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "isprintable", self));
        }
    }

    @Builtin(name = "isspace", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsSpaceNode extends PythonUnaryBuiltinNode {
        @Specialization
        @TruffleBoundary
        static boolean doString(String self) {
            // we cannot use Character.isWhitespace, because Java doesn't consider non-breaking
            // spaces whitespace
            if (self.length() == 0) {
                return false;
            }
            for (int i = 0; i < self.length();) {
                int codePoint = self.codePointAt(i);
                if (!StringUtils.isSpace(codePoint)) {
                    return false;
                }
                i += Character.charCount(codePoint);
            }
            return true;
        }

        @Specialization(replaces = "doString")
        static boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "isspace", self));
        }
    }

    @Builtin(name = "istitle", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsTitleNode extends PythonUnaryBuiltinNode {
        @Specialization
        @TruffleBoundary
        static boolean doString(String self) {
            boolean cased = false;
            boolean previousIsCased = false;
            for (int i = 0; i < self.length();) {
                int codePoint = self.codePointAt(i);

                if (UCharacter.isUUppercase(codePoint) || UCharacter.isTitleCase(codePoint)) {
                    if (previousIsCased) {
                        return false;
                    }
                    previousIsCased = true;
                    cased = true;
                } else if (UCharacter.isULowercase(codePoint)) {
                    if (!previousIsCased) {
                        return false;
                    }
                    previousIsCased = true;
                    cased = true;
                } else {
                    previousIsCased = false;
                }
                i += Character.charCount(codePoint);
            }
            return cased;
        }

        @Specialization(replaces = "doString")
        static boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "istitle", self));
        }
    }

    @Builtin(name = "isupper", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsUpperNode extends PythonUnaryBuiltinNode {
        @Specialization
        @TruffleBoundary
        static boolean doString(String self) {
            boolean hasUpper = false;
            for (int i = 0; i < self.length();) {
                int codePoint = self.codePointAt(i);
                if (UCharacter.isULowercase(codePoint) || UCharacter.isTitleCase(codePoint)) {
                    return false;
                }
                if (!hasUpper && UCharacter.isUUppercase(codePoint)) {
                    hasUpper = true;
                }
                i += Character.charCount(codePoint);
            }
            return hasUpper;
        }

        @Specialization(replaces = "doString")
        static boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "isupper", self));
        }
    }

    @Builtin(name = "zfill", minNumOfPositionalArgs = 2, needsFrame = true)
    @GenerateNodeFactory
    abstract static class ZFillNode extends PythonBinaryBuiltinNode {

        public abstract String executeObject(VirtualFrame frame, String self, Object x);

        @Specialization(limit = "getCallSiteInlineCacheMaxDepth()")
        static String doGeneric(VirtualFrame frame, Object self, Object width,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @CachedLibrary("width") PythonObjectLibrary lib) {
            return zfill(castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "zfill", self), lib.asSizeWithState(width, PArguments.getThreadState(frame)));

        }

        private static String zfill(String self, int width) {
            int len = self.length();
            if (len >= width) {
                return self;
            }
            char[] chars = new char[width];
            int nzeros = width - len;
            int i = 0;
            int sStart = 0;
            if (len > 0) {
                char start = self.charAt(0);
                if (start == '+' || start == '-') {
                    chars[0] = start;
                    i += 1;
                    nzeros++;
                    sStart = 1;
                }
            }
            for (; i < nzeros; i++) {
                chars[i] = '0';
            }
            self.getChars(sStart, len, chars, i);
            return PythonUtils.newString(chars);
        }
    }

    @Builtin(name = "title", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class TitleNode extends PythonUnaryBuiltinNode {

        @Specialization
        static String doString(String self) {
            return doTitle(self);
        }

        @Specialization
        static String doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doTitle(castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "title", self));
        }

        @TruffleBoundary
        private static String doTitle(String self) {
            return UCharacter.toTitleCase(Locale.ROOT, self, null);
        }
    }

    @Builtin(name = "center", minNumOfPositionalArgs = 2, maxNumOfPositionalArgs = 3)
    @GenerateNodeFactory
    @ImportStatic(PString.class)
    abstract static class CenterNode extends PythonBuiltinNode {

        @Specialization(guards = "isNoValue(fill)")
        String doStringInt(String self, int width, @SuppressWarnings("unused") PNone fill) {
            return make(self, width, " ");
        }

        @Specialization(guards = "codePointCount(fill, 0, fill.length()) == 1")
        String doStringIntString(String self, int width, String fill) {
            return make(self, width, fill);
        }

        @Specialization
        String doStringObjectObject(VirtualFrame frame, String self, Object width, Object fill,
                        @Shared("castToIndexNode") @CachedLibrary(limit = "getCallSiteInlineCacheMaxDepth()") PythonObjectLibrary lib,
                        @Shared("castFillNode") @Cached CastToJavaStringCheckedNode castFillNode,
                        @Shared("errorProfile") @Cached("createBinaryProfile()") ConditionProfile errorProfile) {
            String fillStr = PGuards.isNoValue(fill) ? " " : castFillNode.cast(fill, "", fill);
            if (errorProfile.profile(PString.codePointCount(fillStr, 0, fillStr.length()) != 1)) {
                throw raise(TypeError, ErrorMessages.FILL_CHAR_MUST_BE_LENGTH_1);
            }
            return make(self, lib.asSizeWithState(width, PArguments.getThreadState(frame)), fillStr);
        }

        @Specialization
        String doGeneric(VirtualFrame frame, Object self, Object width, Object fill,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Shared("castToIndexNode") @CachedLibrary(limit = "getCallSiteInlineCacheMaxDepth()") PythonObjectLibrary lib,
                        @Shared("castFillNode") @Cached CastToJavaStringCheckedNode castFillNode,
                        @Shared("errorProfile") @Cached("createBinaryProfile()") ConditionProfile errorProfile) {
            String selfStr = castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, __ITER__, self);
            return doStringObjectObject(frame, selfStr, width, fill, lib, castFillNode, errorProfile);
        }

        @TruffleBoundary
        protected String make(String self, int width, String fill) {
            int fillChar = parseCodePoint(fill);
            int len = width - self.length();
            if (len <= 0) {
                return self;
            }
            int half = len / 2;
            if (len % 2 > 0 && width % 2 > 0) {
                half += 1;
            }

            return padding(half, fillChar) + self + padding(len - half, fillChar);
        }

        @TruffleBoundary
        protected static String padding(int len, int codePoint) {
            int[] result = new int[len];
            for (int i = 0; i < len; i++) {
                result[i] = codePoint;
            }
            return new String(result, 0, len);
        }

        @TruffleBoundary
        protected static int parseCodePoint(String fillchar) {
            if (fillchar == null) {
                return ' ';
            }
            return fillchar.codePointAt(0);
        }
    }

    @Builtin(name = "ljust", minNumOfPositionalArgs = 2, maxNumOfPositionalArgs = 3)
    @GenerateNodeFactory
    abstract static class LJustNode extends CenterNode {

        @Override
        @TruffleBoundary
        protected String make(String self, int width, String fill) {
            int fillChar = parseCodePoint(fill);
            int len = width - self.length();
            if (len <= 0) {
                return self;
            }
            return self + padding(len, fillChar);
        }

    }

    @Builtin(name = "rjust", minNumOfPositionalArgs = 2, maxNumOfPositionalArgs = 3)
    @GenerateNodeFactory
    abstract static class RJustNode extends CenterNode {

        @Override
        @TruffleBoundary
        protected String make(String self, int width, String fill) {
            int fillChar = parseCodePoint(fill);
            int len = width - self.length();
            if (len <= 0) {
                return self;
            }
            return padding(len, fillChar) + self;
        }

    }

    @Builtin(name = __GETITEM__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    @TypeSystemReference(PythonArithmeticTypes.class)
    public abstract static class StrGetItemNode extends PythonBinaryBuiltinNode {

        @Specialization
        public String doString(String primary, PSlice slice,
                        @Cached CoerceToIntSlice sliceCast,
                        @Cached ComputeIndices compute,
                        @Cached LenOfRangeNode sliceLen) {
            SliceInfo info = compute.execute(sliceCast.execute(slice), primary.length());
            final int sliceLength = sliceLen.len(info);
            final int start = info.start;
            int stop = info.stop;
            int step = info.step;

            if (step > 0 && stop < start) {
                stop = start;
            }
            if (step == 1) {
                return getSubString(primary, start, stop);
            } else {
                char[] newChars = new char[sliceLength];
                int j = 0;
                for (int i = start; j < sliceLength; i += step) {
                    newChars[j++] = primary.charAt(i);
                }

                return PythonUtils.newString(newChars);
            }
        }

        @Specialization(limit = "getCallSiteInlineCacheMaxDepth()")
        public String doString(VirtualFrame frame, String primary, Object idx,
                        @Cached("createBinaryProfile()") ConditionProfile hasFrame,
                        @CachedLibrary("idx") PythonObjectLibrary lib) {
            int index;
            if (hasFrame.profile(frame != null)) {
                index = lib.asSizeWithState(idx, PArguments.getThreadState(frame));
            } else {
                index = lib.asSize(idx);
            }
            try {
                if (index < 0) {
                    index += primary.length();
                }
                return charAtToString(primary, index);
            } catch (StringIndexOutOfBoundsException | ArithmeticException e) {
                throw raise(IndexError, ErrorMessages.STRING_INDEX_OUT_OF_RANGE);
            }
        }

        @SuppressWarnings("unused")
        @Fallback
        Object doGeneric(Object self, Object other) {
            return PNotImplemented.NOT_IMPLEMENTED;
        }

        @TruffleBoundary
        private static String getSubString(String origin, int start, int stop) {
            char[] chars = new char[stop - start];
            origin.getChars(start, stop, chars, 0);
            return new String(chars);
        }

        @TruffleBoundary
        private static String charAtToString(String primary, int index) {
            char charactor = primary.charAt(index);
            return new String(new char[]{charactor});
        }
    }

    @Builtin(name = __ITER__, minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class IterNode extends PythonUnaryBuiltinNode {
        @Specialization
        PStringIterator doString(String self) {
            return factory().createStringIterator(self);
        }

        @Specialization(replaces = "doString")
        PStringIterator doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, __ITER__, self));
        }
    }

    @Builtin(name = "casefold", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class CasefoldNode extends PythonUnaryBuiltinNode {

        @Specialization
        @TruffleBoundary
        static String doString(String self) {
            return UCharacter.foldCase(self, true);
        }

        @Specialization(replaces = "doString")
        static String doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "casefold", self));
        }
    }

    @Builtin(name = "swapcase", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class SwapCaseNode extends PythonUnaryBuiltinNode {

        private static final int CAPITAL_SIGMA = 0x3A3;

        @Specialization
        @TruffleBoundary
        static String doString(String self) {
            StringBuilder sb = new StringBuilder(self.length());
            for (int i = 0; i < self.length();) {
                int codePoint = self.codePointAt(i);
                int charCount = Character.charCount(codePoint);
                String substr = self.substring(i, i + charCount);
                if (UCharacter.isUUppercase(codePoint)) {
                    // Special case for capital sigma, needed because ICU4J doesn't have the context
                    // of the whole string
                    if (codePoint == CAPITAL_SIGMA) {
                        handleCapitalSigma(self, sb, i, codePoint);
                    } else {
                        sb.append(UCharacter.toLowerCase(Locale.ROOT, substr));
                    }
                } else if (UCharacter.isULowercase(codePoint)) {
                    sb.append(UCharacter.toUpperCase(Locale.ROOT, substr));
                } else {
                    sb.append(substr);
                }
                i += charCount;
            }
            return sb.toString();
        }

        // Adapted from unicodeobject.c:handle_capital_sigma
        private static void handleCapitalSigma(String self, StringBuilder sb, int i, int codePoint) {
            int j;
            for (j = i - 1; j >= 0; j--) {
                if (!Character.isLowSurrogate(self.charAt(j))) {
                    int ch = self.codePointAt(j);
                    if (!UCharacter.hasBinaryProperty(ch, UProperty.CASE_IGNORABLE)) {
                        break;
                    }
                }
            }
            boolean finalSigma = j >= 0 && UCharacter.hasBinaryProperty(codePoint, UProperty.CASED);
            if (finalSigma) {
                for (j = i + 1; j < self.length();) {
                    int ch = self.codePointAt(j);
                    if (!UCharacter.hasBinaryProperty(ch, UProperty.CASE_IGNORABLE)) {
                        break;
                    }
                    j += Character.charCount(ch);
                }
                finalSigma = j == self.length() || !UCharacter.hasBinaryProperty(codePoint, UProperty.CASED);
            }
            sb.appendCodePoint(finalSigma ? 0x3C2 : 0x3C3);
        }

        @Specialization(replaces = "doString")
        static String doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, ErrorMessages.REQUIRES_STR_OBJECT_BUT_RECEIVED_P, "swapcase", self));
        }
    }

    @Builtin(name = "expandtabs", minNumOfPositionalArgs = 1, parameterNames = {"$self", "tabsize"})
    @ArgumentClinic(name = "$self", conversion = ClinicConversion.String)
    @ArgumentClinic(name = "tabsize", conversion = ClinicConversion.Int, defaultValue = "8", useDefaultForNone = true)
    @GenerateNodeFactory
    public abstract static class ExpandTabsNode extends PythonBinaryClinicBuiltinNode {
        @Specialization
        static String doString(String self, int tabsize) {
            StringBuilder sb = PythonUtils.newStringBuilder(self.length());
            int linePos = 0;
            // It's ok to iterate with charAt, we just pass surrogates through
            for (int i = 0; i < self.length(); i++) {
                char ch = PString.charAt(self, i);
                if (ch == '\t') {
                    int incr = tabsize - (linePos % tabsize);
                    for (int j = 0; j < incr; j++) {
                        PythonUtils.append(sb, ' ');
                    }
                    linePos += incr;
                } else {
                    if (ch == '\n' || ch == '\r') {
                        linePos = 0;
                    } else {
                        linePos++;
                    }
                    PythonUtils.append(sb, ch);
                }
            }
            return PythonUtils.sbToString(sb);
        }

        @Override
        protected ArgumentClinicProvider getArgumentClinic() {
            return StringBuiltinsClinicProviders.ExpandTabsNodeClinicProviderGen.INSTANCE;
        }
    }

    protected static int adjustStartIndex(int startIn, int len) {
        if (startIn < 0) {
            int start = startIn + len;
            return start < 0 ? 0 : start;
        }
        return startIn;
    }

    protected static int adjustEndIndex(int endIn, int len) {
        if (endIn > len) {
            return len;
        } else if (endIn < 0) {
            int end = endIn + len;
            return end < 0 ? 0 : end;
        }
        return endIn;
    }
}
