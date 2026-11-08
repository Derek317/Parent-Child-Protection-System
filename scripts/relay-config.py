#!/usr/bin/env python3
"""Validate public relay configuration; never handle account or family secrets."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request
from urllib.parse import urlsplit

def normalize(value):
    if not value:
        return ''
    try:
        url=urlsplit(value)
        host=url.hostname or ''
        valid=(url.scheme=='https' and url.username is None and url.password is None and
               url.port in (None,443) and url.path in ('','/') and not url.query and not url.fragment and
               re.fullmatch(r'[a-z0-9][a-z0-9.-]*\.[a-z]{2,}',host) and
               not host.endswith(('.localhost','.local','.test','.invalid')))
        try:
            ipaddress.ip_address(host)
            valid=False
        except ValueError:
            pass
        if not valid:
            raise ValueError()
        return 'https://'+host
    except (ValueError,TypeError):
        raise ValueError('Use a public HTTPS relay root without credentials, query, fragment or custom port.') from None

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):
        return None

def probe(endpoint):
    request=urllib.request.Request(endpoint+'/health',headers={'Accept':'application/json','User-Agent':'IGGuard-Relay-Check/1.0'})
    with urllib.request.build_opener(NoRedirect).open(request,timeout=15) as response:
        body=response.read(4097)
        if len(body)>4096 or response.status!=200:
            raise ValueError('Relay health check failed.')
        data=json.loads(body)
        if data!={'service':'ig-guard-relay','protocol':1}:
            raise ValueError('Relay protocol does not match this app.')

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--config',type=Path,default=Path(__file__).resolve().parents[1]/'relay/endpoint.json')
    parser.add_argument('--output',type=Path)
    parser.add_argument('--require',action='store_true')
    parser.add_argument('--probe',action='store_true')
    args=parser.parse_args()
    stage='configuration'
    try:
        saved=json.loads(args.config.read_text()).get('base_url','') if args.config.exists() else ''
        endpoint=normalize(os.environ.get('RELAY_BASE_URL') or saved)
        if not endpoint and (args.require or args.probe):
            raise ValueError('Deploy the relay and set RELAY_BASE_URL or relay/endpoint.json before building a release.')
        if args.probe:
            stage='health'
            if os.environ.get('GITHUB_ACTIONS')=='true':
                print(f'::notice title=Relay endpoint::{endpoint} (from '+('RELAY_BASE_URL' if os.environ.get('RELAY_BASE_URL') else 'relay/endpoint.json')+')',flush=True)
            probe(endpoint)
        stage='configuration'
        if args.output:
            args.output.parent.mkdir(parents=True,exist_ok=True)
            args.output.write_text(json.dumps({'base_url':endpoint})+'\n')
        print('Relay configuration verified.' if endpoint else 'Relay not configured; debug checks only, release packaging is blocked.')
        return 0
    except Exception as error:
        # Classify the boundary failure without exposing URLs, response bodies or credentials.
        code='RELAY_CONFIG'
        source='RELAY_BASE_URL repository variable' if os.environ.get('RELAY_BASE_URL') else 'relay/endpoint.json'
        message=f'Invalid relay configuration from {source}; use a public HTTPS root.'
        if stage=='health':
            if isinstance(error,urllib.error.HTTPError):
                code=f'RELAY_HTTP_{error.code}';message='The configured endpoint rejected /health. Check Worker deployment, workers.dev access and any Access policy.'
                server=error.headers.get('Server','').lower()
                if server in ('cloudflare','envoy','nginx'):message+=' Response server: '+server+'.'
                if error.headers.get('CF-Mitigated','').lower()=='challenge':message+=' Cloudflare challenge blocks non-browser clients.'
                try:
                    provider_code=re.search(rb'error code:\s*(1[0-9]{3})',error.read(4096),re.IGNORECASE)
                    if provider_code:message+=' Cloudflare error '+provider_code.group(1).decode('ascii')+'.'
                except Exception:
                    pass
            elif isinstance(error,(urllib.error.URLError,TimeoutError,OSError)):
                code='RELAY_NETWORK';message='The runner could not reach the configured endpoint. Check DNS, TLS and network availability.'
            elif isinstance(error,json.JSONDecodeError):
                code='RELAY_RESPONSE';message='The endpoint returned a non-JSON health response. Check that this Worker, rather than a default page, is deployed at the configured URL.'
            else:
                code='RELAY_PROTOCOL';message='The health response does not identify IG Guard relay protocol 1. Check the deployed Worker and endpoint.'
        prefix='::error title=Relay verification::' if os.environ.get('GITHUB_ACTIONS')=='true' else ''
        print(f'{prefix}[{code}] {message}',file=sys.stderr)
        return 2

if __name__=='__main__':
    sys.exit(main())
