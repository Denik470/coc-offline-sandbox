package com.sistr.safejni;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/* JADX INFO: compiled from: r8-map-id-0992679c270b71320d696be6576805bb3b69c94e7fc64e9a74e601f3f40f0ed2 */
/* JADX INFO: loaded from: classes2.dex */
public class NativeProxy implements InvocationHandler {
    @Override // java.lang.reflect.InvocationHandler
    public native Object invoke(Object obj, Method method, Object[] objArr);
}
