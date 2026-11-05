package lab.franziska.pixelbridge;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.util.Arrays;
import java.io.File;

/** Pixel-only Android Keystore encryption. No global providers, shared storage or backup exports.
 * Call after owner opt-in, off the UI thread. No Android companion credentials are consulted.
 */
public final class GatewayAndroidCredentials {
    private static final String ALIAS="franziska.glass.gateway.credentials.v1";
    private static GatewayCredentials singleton;
    private GatewayAndroidCredentials(){}
    public static synchronized GatewayCredentials open(Context context) throws Exception {
        if(android.os.Build.VERSION.SDK_INT<23)throw new GatewayException("ANDROID_VERSION_UNSUPPORTED");
        if(singleton!=null)return singleton;
        final KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
        final File files=context.getFilesDir().getCanonicalFile();
        if(!keys.containsAlias(ALIAS)){
            if(new File(files,"gateway-assist-private/credentials.bin").exists())throw new GatewayException("CREDENTIAL_KEY_UNAVAILABLE");
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());generator.generateKey();
        }
        final SecretKey key=(SecretKey)keys.getKey(ALIAS,null);
        singleton=new GatewayCredentials(files,new GatewayCredentials.Protector(){
            public byte[] seal(byte[] plain)throws Exception{
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);
                cipher.updateAAD(ALIAS.getBytes(GatewayJson.UTF8));byte[] iv=cipher.getIV();if(iv.length!=12)throw new GatewayException("CREDENTIAL_STORAGE_FAILED");
                byte[] encrypted=cipher.doFinal(plain),result=new byte[1+iv.length+encrypted.length];result[0]=1;
                System.arraycopy(iv,0,result,1,iv.length);System.arraycopy(encrypted,0,result,13,encrypted.length);Arrays.fill(encrypted,(byte)0);return result;
            }
            public byte[] open(byte[] encrypted)throws Exception{
                if(encrypted.length<29||encrypted[0]!=1)throw new GatewayException("CREDENTIAL_STORAGE_FAILED");
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,encrypted,1,12));
                cipher.updateAAD(ALIAS.getBytes(GatewayJson.UTF8));return cipher.doFinal(encrypted,13,encrypted.length-13);
            }
        });return singleton;
    }
}
