/*
 * Copyright (c) 2008 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
 
package org.sonatype.plexus.components.sec.dispatcher;


import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.plexus.components.cipher.DefaultPlexusCipher;
import org.sonatype.plexus.components.cipher.PlexusCipher;
import org.sonatype.plexus.components.cipher.PlexusCipherException;
import org.sonatype.plexus.components.sec.dispatcher.model.SettingsSecurity;

/**
 * @author Oleg Gusakov
 */
@Singleton
@Named
public class DefaultSecDispatcher
  implements SecDispatcher
{
    private static final String DEFAULT_CONFIGURATION = "~/.settings-security.xml";

    public static final String SYSTEM_PROPERTY_SEC_LOCATION = "settings.security";
    
    public static final String TYPE_ATTR = "type";

    public static final char ATTR_START = '[';

    public static final char ATTR_STOP  = ']';

    /**
     * DefaultHandler
     */
    protected final PlexusCipher _cipher;

    /**
     * All available dispatchers
     */
    protected final Map<String, PasswordDecryptor> _decryptors;

    /**
     * Configuration file
     */
    protected String _configurationFile;

    @Inject
    public DefaultSecDispatcher( final PlexusCipher _cipher,
                                 final Map<String, PasswordDecryptor> _decryptors,
                                 @Named( "${_configurationFile:-" + DEFAULT_CONFIGURATION + "}" )
                                 final String _configurationFile )
    {
        this._cipher = _cipher;
        this._decryptors = _decryptors;
        this._configurationFile = _configurationFile;
    }

    /**
     * Ctor to be used in tests and other simplified cases (no decryptors and config).
     */
    public DefaultSecDispatcher( final PlexusCipher _cipher ) {
        this( _cipher, new HashMap<String, PasswordDecryptor>(), DEFAULT_CONFIGURATION );
    }

    // ---------------------------------------------------------------

    @Override
    public String decrypt( String str )
        throws SecDispatcherException
    {
        if( ! isEncryptedString( str ) )
            return str;
        
        String bare;
        
        try
        {
            bare = _cipher.unDecorate( str );
        }
        catch ( PlexusCipherException e1 )
        {
            throw new SecDispatcherException( e1 );
        }
        
        try
        {
            Map<String, String> attr = stripAttributes( bare );
            
            String res;

            SettingsSecurity sec = getSec();
            
            if( attr == null || attr.get( "type" ) == null )
            {
                String master = getMaster( sec );
                
                res = _cipher.decrypt( bare, master );
            }
            else
            {
                String type = attr.get( TYPE_ATTR );
                
                if( _decryptors == null )
                    throw new SecDispatcherException( "plexus container did not supply any required dispatchers - cannot lookup "+type );
                
                Map<String, String> conf = SecUtil.getConfig( sec, type );
                
                PasswordDecryptor dispatcher = _decryptors.get( type );
                
                if( dispatcher == null )
                    throw new SecDispatcherException( "no dispatcher for hint "+type );
                
                String pass = attr == null ? bare : strip( bare );
                
                return dispatcher.decrypt( pass, attr, conf );
            }
            
            return res;
        }
        catch ( Exception e )
        {
            throw new SecDispatcherException(e);
        }
    }
    
    private String strip( String str )
    {
        int pos = str.indexOf( ATTR_STOP );
        
        if( pos == str.length() )
            return null;
        
        if( pos != -1 )
            return str.substring( pos+1 );
        
        return str;
    }
    
    private Map<String, String> stripAttributes( String str )
    {
        int start = str.indexOf( ATTR_START );
        int stop = str.indexOf( ATTR_STOP );
        if ( start != -1 && stop != -1 && stop > start )
        {
            if( stop == start+1 )
                return null;
            
            String attrs = str.substring( start+1, stop ).trim();
            
            if( attrs.length() < 1 )
                return null;
            
            Map<String, String> res = null;
            
            StringTokenizer st = new StringTokenizer( attrs, ", " );
            
            while( st.hasMoreTokens() )
            {
                if( res == null )
                    res = new HashMap<>( st.countTokens() );
                
                String pair = st.nextToken();
                
                int pos = pair.indexOf( '=' );
                
                if( pos == -1 )
                    continue;
                
                String key = pair.substring( 0, pos ).trim();

                if( pos == pair.length() )
                {
                    res.put( key, null );
                    continue;
                }
                
                String val = pair.substring( pos+1 );
                
                res.put(  key, val.trim() );
            }
            
            return res;
        }
        
        return null;
    }

    //----------------------------------------------------------------------------

    private boolean isEncryptedString( String str )
    {
        if( str == null )
            return false;

        return _cipher.isEncryptedString( str );
    }

    //----------------------------------------------------------------------------

    private SettingsSecurity getSec()
    throws SecDispatcherException
    {
        String location = System.getProperty( SYSTEM_PROPERTY_SEC_LOCATION
                                              , getConfigurationFile()
                                            );
        String realLocation = location.charAt( 0 ) == '~' 
            ? System.getProperty( "user.home" ) + location.substring( 1 )
            : location
            ;
        
        SettingsSecurity sec = SecUtil.read( realLocation, true );
        
        if( sec == null )
            throw new SecDispatcherException( "cannot retrieve master password. Please check that "+realLocation+" exists and has data" );
        
        return sec;
    }

    //----------------------------------------------------------------------------

    private String getMaster( SettingsSecurity sec )
    throws SecDispatcherException
    {
        String master = sec.getMaster();
        
        if( master == null )
            throw new SecDispatcherException( "master password is not set" );
        
        try
        {
            return _cipher.decryptDecorated( master, SYSTEM_PROPERTY_SEC_LOCATION );
        }
        catch ( PlexusCipherException e )
        {
            throw new SecDispatcherException(e);
        }
    }
    //---------------------------------------------------------------
    public String getConfigurationFile()
    {
        return _configurationFile;
    }

    public void setConfigurationFile( String file )
    {
        _configurationFile = file;
    }

    //---------------------------------------------------------------

    private static boolean propertyExists( String [] values, String [] av )
    {
        if( values != null )
        {
            for ( String item : values ) {
                String p = System.getProperty( item );

                if ( p != null ) {
                    return true;
                }
            }
        
            if( av != null )
                for ( String value : values )
                    for ( String s : av ) {
                        if ( ( "--" + value ).equals( s ) ) {
                            return true;
                        }
                    }
        }
        
        return false;
    }
    
    private static void usage()
    {
        System.out.println( "usage: java -jar ...jar [-m|-p]\n-m: encrypt master password\n-p: encrypt password" );
    }

    //---------------------------------------------------------------

    public static void main( String[] args )
    throws Exception
    {
        if( args == null || args.length < 1 )
        {
            usage();
            return;
        }
        
        if( "-m".equals( args[0] ) || propertyExists( SYSTEM_PROPERTY_MASTER_PASSWORD, args ) ) 
            show( true );
        else if( "-p".equals( args[0] ) || propertyExists( SYSTEM_PROPERTY_SERVER_PASSWORD, args ) )
            show( false );
        else
            usage();
    }

    //---------------------------------------------------------------

    private static void show( boolean showMaster )
    throws Exception
    {
        if( showMaster )
            System.out.print("\nsettings master password\n");
        else
            System.out.print("\nsettings server password\n");
        
        System.out.print("enter password: ");
        
        BufferedReader r = new BufferedReader( new InputStreamReader( System.in ) );
        
        String pass = r.readLine();
        
        System.out.println("\n");
        
        DefaultPlexusCipher dc = new DefaultPlexusCipher();
        DefaultSecDispatcher dd = new DefaultSecDispatcher( dc );

        if( showMaster )
            System.out.println( dc.encryptAndDecorate( pass, DefaultSecDispatcher.SYSTEM_PROPERTY_SEC_LOCATION ) );
        else
        {
            SettingsSecurity sec = dd.getSec();
            System.out.println( dc.encryptAndDecorate( pass, dd.getMaster(sec) ) );
        }
    }
}
