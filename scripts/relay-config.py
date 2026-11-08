#!/usr/bin/env python3
"""Validate public relay configuration; never handle account or family secrets."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
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
    request=urllib.request.Request(endpoint+'/health',headers={'Accept':'application/json'})
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
    try:
        saved=json.loads(args.config.read_text()).get('base_url','') if args.config.exists() else ''
        endpoint=normalize(os.environ.get('RELAY_BASE_URL') or saved)
        if not endpoint and (args.require or args.probe):
            raise ValueError('Deploy the relay and set RELAY_BASE_URL or relay/endpoint.json before building a release.')
        if args.probe:
            probe(endpoint)
        if args.output:
            args.output.parent.mkdir(parents=True,exist_ok=True)
            args.output.write_text(json.dumps({'base_url':endpoint})+'\n')
        print('Relay configuration verified.' if endpoint else 'Relay not configured; debug checks only, release packaging is blocked.')
        return 0
    except Exception:
        # Error details may contain a supplied URL or credentials: expose a fixed setup code only.
        print('[RELAY_CONFIG] Relay configuration or health verification failed. Use a deployed HTTPS relay root; credentials and details are omitted.',file=sys.stderr)
        return 2

if __name__=='__main__':
    sys.exit(main())
