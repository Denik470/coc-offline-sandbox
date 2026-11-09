package com.supercell.titan;
import android.app.*;import android.content.*;import android.content.res.*;import android.view.Surface;
public class PurchaseManager {
public static class ProductInfo {}
public static native void billingProductBought(String str, String str2, String str3, String str4, String str5);
public static native void billingProductCanceled(String str);
public static native void billingProductFailed(String str, String str2, int i);
public static native void billingSetMarketplace(String str);
public static native void updateBillingProductDetails(ProductInfo[] productInfoArr, String str, int i);
}
