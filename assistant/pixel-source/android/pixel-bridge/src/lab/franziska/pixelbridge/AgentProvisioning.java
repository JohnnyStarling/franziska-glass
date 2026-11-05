package lab.franziska.pixelbridge;

import java.io.*;
import java.net.InetAddress;
import java.security.KeyStore;
import java.util.Properties;
import javax.net.ssl.*;

/** Separate owner-provisioned credentials; never reads the Glass pairing identity. */
final class AgentProvisioning {
    static AgentEndpoint open(File files, AgentEndpoint.Bridge bridge) throws Exception {
        File directory = new File(files,"agent-endpoint");
        Properties config = new Properties();
        FileInputStream input = new FileInputStream(new File(directory,"endpoint.properties"));
        try { if(input.getChannel().size()>4096) throw new IOException(); config.load(input); } finally {input.close();}
        String address=config.getProperty("bind","");
        // Literal IPv4 only: no DNS, wildcards, fallback interface, or ambient route discovery.
        if(!address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IOException();
        String token=config.getProperty("token","");
        char[] password=config.getProperty("storePassword","").toCharArray();
        int port=Integer.parseInt(config.getProperty("port","0"));
        if(port<1024 || port>65535 || password.length<16) throw new IOException();
        KeyStore keys=KeyStore.getInstance("PKCS12");
        input=new FileInputStream(new File(directory,"server.p12"));
        try { if(input.getChannel().size()>65536) throw new IOException(); keys.load(input,password); } finally {input.close();}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys,password);
        java.util.Arrays.fill(password,'\0');
        SSLContext tls=SSLContext.getInstance("TLSv1.2"); tls.init(km.getKeyManagers(),null,null);
        return new AgentEndpoint(tls,InetAddress.getByName(address),port,token,bridge);
    }
}
