package org.keycloak.exportimport;

import org.keycloak.provider.ProviderFactory;

/**
 * Provider plugin interface for importing clients from an arbitrary configuration format
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public interface ClientDescriptionConverterFactory extends ProviderFactory<ClientDescriptionConverter> {

    boolean isSupported(String description);

}
